//! Nexora Mesh core: transport-independent framing, relay queue, and power-aware policy.
//! Framing is not encryption or authentication. Relay payloads remain opaque.
//!
//! Host platforms (Android / iOS / desktop) should call the public API or the thin C ABI
//! in the `ffi` module. No homemade crypto lives here.

use std::collections::{HashMap, HashSet, VecDeque};
use std::convert::TryInto;
use std::time::{SystemTime, UNIX_EPOCH};

pub const HEADER_LEN: usize = 4;
pub const MAX_FRAME_LEN: usize = 1024 * 1024;
pub const DEFAULT_MAX_QUEUE: usize = 256;
pub const DEFAULT_MAX_HOPS: u8 = 8;
pub const DEFAULT_TTL_SECS: u64 = 3600;

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

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    pub message_id: [u8; 16],
    pub destination_token: [u8; 16],
    pub ttl_hops: u8,
    pub expires_at: u64,
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

#[derive(Clone, Debug)]
pub struct RelayQueue {
    max_size: usize,
    queue: VecDeque<Envelope>,
    seen: HashSet<[u8; 16]>,
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

    pub fn enqueue(&mut self, env: Envelope, now: u64) -> bool {
        if env.is_expired(now) || !env.can_forward() || self.seen.contains(&env.message_id) {
            return false;
        }
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
            return Some(env);
        }
        None
    }

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

    pub fn snapshot(&self) -> Vec<Envelope> {
        self.queue.iter().cloned().collect()
    }

    pub fn restore(&mut self, items: Vec<Envelope>, now: u64) {
        self.queue.clear();
        self.seen.clear();
        self.by_dest.clear();
        for env in items {
            let _ = self.enqueue(env, now);
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct PowerSignals {
    pub battery_percent: u8,
    pub is_charging: bool,
    pub low_power_mode: bool,
    pub is_foreground: bool,
    pub recent_connection: bool,
    pub urgent_pending: bool,
}

pub fn recommended_scan_interval_secs(signals: &PowerSignals) -> u64 {
    if signals.urgent_pending && signals.is_foreground {
        return 5;
    }
    if signals.is_charging && signals.is_foreground {
        return 10;
    }
    if signals.recent_connection {
        return 15;
    }
    if signals.low_power_mode || signals.battery_percent < 15 {
        return 120;
    }
    if signals.battery_percent < 30 {
        return 60;
    }
    if signals.is_foreground {
        return 20;
    }
    45
}

pub fn may_forward(signals: &PowerSignals) -> bool {
    if signals.low_power_mode && signals.battery_percent < 10 && !signals.is_charging {
        return false;
    }
    true
}

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

    pub fn inject_a_to_c(&mut self, env: Envelope, now: u64) {
        if let Some(b) = self.nodes.get_mut("B") {
            let _ = b.enqueue(env, now);
        }
    }

    pub fn relay_b_to_c(&mut self, now: u64) -> Option<Envelope> {
        let env = self.nodes.get_mut("B")?.dequeue_for_forward(now)?;
        if let Some(c) = self.nodes.get_mut("C") {
            let _ = c.enqueue(env.clone(), now);
        }
        Some(env)
    }
}

pub mod ffi {
    use super::*;
    use std::os::raw::{c_int, c_uchar};

    #[no_mangle]
    pub extern "C" fn nm_recommended_scan_interval_secs(
        battery_percent: c_uchar,
        flags: c_uchar,
    ) -> u64 {
        let signals = PowerSignals {
            battery_percent,
            is_charging: flags & 1 != 0,
            low_power_mode: flags & 2 != 0,
            is_foreground: flags & 4 != 0,
            recent_connection: flags & 8 != 0,
            urgent_pending: flags & 16 != 0,
        };
        recommended_scan_interval_secs(&signals)
    }

    #[no_mangle]
    pub extern "C" fn nm_may_forward(battery_percent: c_uchar, flags: c_uchar) -> c_int {
        let signals = PowerSignals {
            battery_percent,
            is_charging: flags & 1 != 0,
            low_power_mode: flags & 2 != 0,
            is_foreground: flags & 4 != 0,
            recent_connection: flags & 8 != 0,
            urgent_pending: flags & 16 != 0,
        };
        if may_forward(&signals) {
            1
        } else {
            0
        }
    }
}

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
    fn a_b_c_in_memory_simulation() {
        let mut sim = MeshSim::new();
        let n = now();
        let dest_c = token(0xC);
        let env = Envelope::new(mid(42), dest_c, 4, 3600, b"hello from A via B".to_vec());
        sim.inject_a_to_c(env, n);
        assert_eq!(sim.nodes["B"].len(), 1);
        let forwarded = sim.relay_b_to_c(n).expect("forward");
        assert_eq!(forwarded.ttl_hops, 3);
        let delivered = sim
            .nodes
            .get_mut("C")
            .unwrap()
            .take_for_destination(&dest_c, n);
        assert_eq!(delivered.len(), 1);
        assert_eq!(delivered[0].payload, b"hello from A via B");
    }

    #[test]
    fn ffi_scan_interval() {
        let secs = ffi::nm_recommended_scan_interval_secs(50, 4);
        assert!(secs <= 30);
        let secs2 = ffi::nm_recommended_scan_interval_secs(10, 2);
        assert!(secs2 >= 60);
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
