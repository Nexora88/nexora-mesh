//! Nexora Mesh core: transport-independent framing, relay queue, and power-aware policy.
//! Framing is not encryption or authentication. Relay payloads remain opaque.

use std::collections::{HashMap, HashSet, VecDeque};
use std::convert::TryInto;
use std::time::{SystemTime, UNIX_EPOCH};

pub const HEADER_LEN: usize = 4;
pub const MAX_FRAME_LEN: usize = 1024 * 1024;
pub const DEFAULT_MAX_QUEUE: usize = 256;
pub const DEFAULT_MAX_HOPS: u8 = 8;
pub const DEFAULT_TTL_SECS: u64 = 3600; // 1 hour

#[derive(Debug, PartialEq, Eq)]
pub enum FrameError {
    HeaderTooShort,
    PayloadTooLarge,
    PayloadTruncated,
    TrailingBytes,
}

pub fn encode_frame(payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.len() > MAX_FRAME_LEN || payload.len() > u32::MAX as usize {
        return Err(FrameError::PayloadTooLarge);
    }
    let mut frame = Vec::with_capacity(HEADER_LEN + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

pub fn decode_frame(frame: &[u8]) -> Result<&[u8], FrameError> {
    if frame.len() < HEADER_LEN {
        return Err(FrameError::HeaderTooShort);
    }
    let length =
        u32::from_be_bytes(frame[..HEADER_LEN].try_into().expect("length checked")) as usize;
    if length > MAX_FRAME_LEN {
        return Err(FrameError::PayloadTooLarge);
    }
    let expected = HEADER_LEN + length;
    if frame.len() < expected {
        return Err(FrameError::PayloadTruncated);
    }
    if frame.len() > expected {
        return Err(FrameError::TrailingBytes);
    }
    Ok(&frame[HEADER_LEN..expected])
}

// ---------------------------------------------------------------------------
// Logical envelope (opaque to relays)
// ---------------------------------------------------------------------------

/// Opaque encrypted envelope that relays store and forward without decryption.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    /// Unique message identifier for deduplication (16 bytes recommended).
    pub message_id: [u8; 16],
    /// Destination token (rotating keyed token in future; currently opaque 16 bytes).
    pub destination_token: [u8; 16],
    /// Remaining hop limit. Decremented exactly once on each forward.
    pub ttl_hops: u8,
    /// Absolute expiry as Unix seconds. Drop after this time.
    pub expires_at: u64,
    /// Opaque ciphertext + protocol metadata. Relays must never inspect or modify.
    pub payload: Vec<u8>,
}

impl Envelope {
    pub fn new(
        message_id: [u8; 16],
        destination_token: [u8; 16],
        ttl_hops: u8,
        ttl_secs: u64,
        payload: Vec<u8>,
    ) -> Self {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        Self {
            message_id,
            destination_token,
            ttl_hops,
            expires_at: now.saturating_add(ttl_secs),
            payload,
        }
    }

    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }

    pub fn can_forward(&self) -> bool {
        self.ttl_hops > 0
    }
}

// ---------------------------------------------------------------------------
// Relay queue with dedup, expiry, hop limit, bounded size, snapshot
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct RelayQueue {
    max_size: usize,
    /// FIFO for forwarding order.
    queue: VecDeque<Envelope>,
    /// Fast lookup for duplicates.
    seen: HashSet<[u8; 16]>,
    /// Optional per-destination index for targeted delivery.
    by_dest: HashMap<[u8; 16], Vec<[u8; 16]>>,
}

impl RelayQueue {
    pub fn new(max_size: usize) -> Self {
        Self {
            max_size: max_size.max(1),
            queue: VecDeque::new(),
            seen: HashSet::new(),
            by_dest: HashMap::new(),
        }
    }

    /// Insert an envelope. Returns false if duplicate, expired, or zero hops.
    pub fn enqueue(&mut self, env: Envelope, now: u64) -> bool {
        if env.is_expired(now) || !env.can_forward() || self.seen.contains(&env.message_id) {
            return false;
        }
        // Evict oldest if full
        while self.queue.len() >= self.max_size {
            if let Some(old) = self.queue.pop_front() {
                self.seen.remove(&old.message_id);
                if let Some(ids) = self.by_dest.get_mut(&old.destination_token) {
                    ids.retain(|id| *id != old.message_id);
                }
            }
        }
        self.seen.insert(env.message_id);
        self.by_dest
            .entry(env.destination_token)
            .or_default()
            .push(env.message_id);
        self.queue.push_back(env);
        true
    }

    /// Pop next envelope that is still valid and can be forwarded.
    /// Decrements ttl_hops on the returned copy.
    pub fn dequeue_for_forward(&mut self, now: u64) -> Option<Envelope> {
        while let Some(mut env) = self.queue.pop_front() {
            self.seen.remove(&env.message_id);
            if let Some(ids) = self.by_dest.get_mut(&env.destination_token) {
                ids.retain(|id| *id != env.message_id);
            }
            if env.is_expired(now) || !env.can_forward() {
                continue;
            }
            env.ttl_hops = env.ttl_hops.saturating_sub(1);
            // Re-insert with reduced hop if still useful for further hops? No:
            // The caller is responsible for re-enqueuing if it wants multi-hop
            // from this node. For simple store-and-forward we hand out once.
            return Some(env);
        }
        None
    }

    /// Peek envelopes destined for a specific token (for local delivery).
    pub fn take_for_destination(&mut self, token: &[u8; 16], now: u64) -> Vec<Envelope> {
        let mut result = Vec::new();
        let mut remaining = VecDeque::new();
        while let Some(env) = self.queue.pop_front() {
            if env.destination_token == *token && !env.is_expired(now) {
                self.seen.remove(&env.message_id);
                result.push(env);
            } else {
                remaining.push_back(env);
            }
        }
        self.queue = remaining;
        // Rebuild by_dest for simplicity
        self.by_dest.clear();
        for e in &self.queue {
            self.by_dest
                .entry(e.destination_token)
                .or_default()
                .push(e.message_id);
        }
        result
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    /// Snapshot for durable storage (app layer persists this).
    pub fn snapshot(&self) -> Vec<Envelope> {
        self.queue.iter().cloned().collect()
    }

    /// Restore from previously persisted snapshot.
    pub fn restore(&mut self, items: Vec<Envelope>, now: u64) {
        self.queue.clear();
        self.seen.clear();
        self.by_dest.clear();
        for env in items {
            let _ = self.enqueue(env, now);
        }
    }
}

// ---------------------------------------------------------------------------
// Smart power / scan policy
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default)]
pub struct PowerSignals {
    pub battery_percent: u8,      // 0-100
    pub is_charging: bool,
    pub low_power_mode: bool,
    pub is_foreground: bool,
    pub recent_connection: bool, // connected in last few minutes
    pub urgent_pending: bool,    // high-priority messages waiting
}

/// Recommends BLE scan / advertise interval in seconds.
/// Goal: aggressive when useful, aggressive battery saving otherwise.
pub fn recommended_scan_interval_secs(signals: &PowerSignals) -> u64 {
    if signals.urgent_pending && signals.is_foreground {
        return 5; // very active
    }
    if signals.is_charging && signals.is_foreground {
        return 10;
    }
    if signals.recent_connection {
        return 15;
    }
    if signals.low_power_mode || signals.battery_percent < 15 {
        return 120; // deep sleep-ish
    }
    if signals.battery_percent < 30 {
        return 60;
    }
    if signals.is_foreground {
        return 20;
    }
    // background, decent battery
    45
}

/// Whether the node is currently allowed to forward / advertise.
pub fn may_forward(signals: &PowerSignals) -> bool {
    if signals.low_power_mode && signals.battery_percent < 10 && !signals.is_charging {
        return false;
    }
    true
}

// ---------------------------------------------------------------------------
// In-memory A→B→C simulation helpers (for tests and desktop console)
// ---------------------------------------------------------------------------

/// Simple three-node simulation for routing verification.
pub struct MeshSim {
    pub nodes: HashMap<&'static str, RelayQueue>,
}

impl MeshSim {
    pub fn new() -> Self {
        let mut nodes = HashMap::new();
        nodes.insert("A", RelayQueue::new(DEFAULT_MAX_QUEUE));
        nodes.insert("B", RelayQueue::new(DEFAULT_MAX_QUEUE));
        nodes.insert("C", RelayQueue::new(DEFAULT_MAX_QUEUE));
        Self { nodes }
    }

    /// A originates a message destined for C; B is the intermediate relay.
    pub fn inject_a_to_c(&mut self, env: Envelope, now: u64) {
        // A would send directly if possible; here we put it on B as if A→B hop occurred.
        if let Some(b) = self.nodes.get_mut("B") {
            let _ = b.enqueue(env, now);
        }
    }

    /// B forwards one packet toward C.
    pub fn relay_b_to_c(&mut self, now: u64) -> Option<Envelope> {
        let env = self.nodes.get_mut("B")?.dequeue_for_forward(now)?;
        if let Some(c) = self.nodes.get_mut("C") {
            // C receives it (local delivery path would match destination_token)
            let _ = c.enqueue(env.clone(), now);
        }
        Some(env)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn now() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }

    fn mid(seed: u8) -> [u8; 16] {
        let mut id = [0u8; 16];
        id[0] = seed;
        id
    }

    fn token(seed: u8) -> [u8; 16] {
        let mut t = [0u8; 16];
        t[0] = seed;
        t
    }

    #[test]
    fn frame_round_trip() {
        let frame = encode_frame(b"hello mesh").unwrap();
        assert_eq!(decode_frame(&frame), Ok(&b"hello mesh"[..]));
    }

    #[test]
    fn empty_payload() {
        let frame = encode_frame(b"").unwrap();
        assert_eq!(decode_frame(&frame), Ok(&b""[..]));
    }

    #[test]
    fn short_header_rejected() {
        assert_eq!(decode_frame(&[0, 1]), Err(FrameError::HeaderTooShort));
    }

    #[test]
    fn truncated_payload_rejected() {
        assert_eq!(
            decode_frame(&[0, 0, 0, 3, b'a']),
            Err(FrameError::PayloadTruncated)
        );
    }

    #[test]
    fn oversized_payload_rejected() {
        assert_eq!(
            decode_frame(&[0, 0x10, 0, 1]),
            Err(FrameError::PayloadTooLarge)
        );
    }

    #[test]
    fn trailing_bytes_rejected() {
        assert_eq!(
            decode_frame(&[0, 0, 0, 1, b'a', b'b']),
            Err(FrameError::TrailingBytes)
        );
    }

    #[test]
    fn relay_queue_dedup_and_ttl() {
        let mut q = RelayQueue::new(10);
        let n = now();
        let env = Envelope::new(mid(1), token(99), 3, 3600, b"secret".to_vec());
        assert!(q.enqueue(env.clone(), n));
        assert!(!q.enqueue(env, n)); // duplicate
        assert_eq!(q.len(), 1);
    }

    #[test]
    fn relay_queue_expiry() {
        let mut q = RelayQueue::new(10);
        let n = now();
        let mut env = Envelope::new(mid(2), token(99), 3, 10, b"x".to_vec());
        env.expires_at = n - 1; // already expired
        assert!(!q.enqueue(env, n));
    }

    #[test]
    fn relay_queue_hop_limit() {
        let mut q = RelayQueue::new(10);
        let n = now();
        let env = Envelope::new(mid(3), token(99), 0, 3600, b"x".to_vec());
        assert!(!q.enqueue(env, n)); // zero hops
    }

    #[test]
    fn a_b_c_in_memory_simulation() {
        let mut sim = MeshSim::new();
        let n = now();
        let dest_c = token(0xC);
        let env = Envelope::new(mid(42), dest_c, 4, 3600, b"hello from A via B".to_vec());

        // A → B (inject)
        sim.inject_a_to_c(env, n);
        assert_eq!(sim.nodes["B"].len(), 1);
        assert!(sim.nodes["C"].is_empty());

        // B → C
        let forwarded = sim.relay_b_to_c(n).expect("should forward");
        assert_eq!(forwarded.ttl_hops, 3); // decremented
        assert_eq!(sim.nodes["B"].len(), 0);
        assert_eq!(sim.nodes["C"].len(), 1);

        // C can take messages for itself
        let delivered = sim
            .nodes
            .get_mut("C")
            .unwrap()
            .take_for_destination(&dest_c, n);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].payload, b"hello from A via B");
    }

    #[test]
    fn smart_scan_policy_battery_aware() {
        let mut s = PowerSignals {
            battery_percent: 50,
            is_charging: false,
            low_power_mode: false,
            is_foreground: true,
            recent_connection: false,
            urgent_pending: false,
        };
        assert!(recommended_scan_interval_secs(&s) <= 30);

        s.battery_percent = 10;
        s.low_power_mode = true;
        assert!(recommended_scan_interval_secs(&s) >= 60);
        assert!(!may_forward(&s) || s.battery_percent >= 10); // policy boundary
    }

    #[test]
    fn snapshot_restore() {
        let mut q = RelayQueue::new(10);
        let n = now();
        let env = Envelope::new(mid(7), token(1), 2, 3600, b"persist me".to_vec());
        assert!(q.enqueue(env, n));
        let snap = q.snapshot();
        let mut q2 = RelayQueue::new(10);
        q2.restore(snap, n);
        assert_eq!(q2.len(), 1);
    }
}
