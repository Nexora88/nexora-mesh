//! Nexora Mesh core — framing, ephemeral identities, blind multi-hop envelopes,
//! relay queue, power policy, and a thin C ABI for Android/iOS radio bridges.
//!
//! Security honesty:
//! - Framing and routing structures are transport-independent.
//! - The KDF / blind-target helpers in this crate are **development placeholders**.
//!   Production must replace them with reviewed libraries (e.g. HKDF + HMAC-SHA256)
//!   before claiming privacy or untraceability.
//! - Relays never decrypt payloads; they only match blind target tags and forward.

use std::collections::{HashMap, HashSet, VecDeque};
use std::convert::TryInto;
use std::time::{SystemTime, UNIX_EPOCH};

pub const HEADER_LEN: usize = 4;
pub const MAX_FRAME_LEN: usize = 1024 * 1024;
pub const DEFAULT_MAX_QUEUE: usize = 256;
pub const DEFAULT_MAX_HOPS: u8 = 5;
pub const DEFAULT_TTL_SECS: u64 = 3600;
pub const EPHEMERAL_ROTATION_SECS: u64 = 15 * 60;
pub const WIRE_VERSION: u8 = 1;
pub const MSG_ID_LEN: usize = 16;
pub const BLIND_TARGET_LEN: usize = 16;
pub const EPHEMERAL_ID_LEN: usize = 16;

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

fn dev_keyed_hash(key: &[u8], data: &[u8]) -> [u8; 16] {
    let mut out = [0u8; 16];
    let mut state: u64 = 0x9e37_79b9_7f4a_7c15;
    for (i, &b) in key.iter().enumerate() {
        state = state
            .wrapping_mul(0x1000_0000_01b3)
            .wrapping_add(b as u64)
            .wrapping_add(i as u64);
        out[i % 16] ^= state.to_le_bytes()[i % 8];
    }
    for (i, &b) in data.iter().enumerate() {
        state = state
            .wrapping_mul(0xc6a4_a793_5bd1_e995)
            .wrapping_add(b as u64)
            .rotate_left(((i % 17) + 1) as u32);
        let bytes = state.to_le_bytes();
        out[i % 16] ^= bytes[i % 8];
        out[(i + 3) % 16] = out[(i + 3) % 16].wrapping_add(bytes[(i + 1) % 8]);
    }
    for round in 0..4 {
        for i in 0..16 {
            out[i] = out[i]
                .wrapping_add(out[(i + 1) % 16])
                .rotate_left(1)
                ^ (round as u8).wrapping_mul(0x5b);
        }
    }
    out
}

#[derive(Clone, Debug)]
pub struct EphemeralIdRotator {
    identity_secret: Vec<u8>,
    rotation_secs: u64,
    pub current_slot: u64,
    current_id: [u8; EPHEMERAL_ID_LEN],
}

impl EphemeralIdRotator {
    pub fn new(identity_secret: Vec<u8>) -> Self {
        let mut r = Self {
            identity_secret,
            rotation_secs: EPHEMERAL_ROTATION_SECS,
            current_slot: 0,
            current_id: [0u8; EPHEMERAL_ID_LEN],
        };
        r.force_rotate(unix_now());
        r
    }

    pub fn with_rotation_secs(identity_secret: Vec<u8>, rotation_secs: u64) -> Self {
        let mut r = Self::new(identity_secret);
        r.rotation_secs = rotation_secs.max(60);
        r.force_rotate(unix_now());
        r
    }

    pub fn current_id(&self) -> [u8; EPHEMERAL_ID_LEN] {
        self.current_id
    }

    pub fn tick(&mut self, now: u64) -> bool {
        let slot = now / self.rotation_secs;
        if slot != self.current_slot {
            self.force_rotate(now);
            true
        } else {
            false
        }
    }

    pub fn force_rotate(&mut self, now: u64) {
        self.current_slot = now / self.rotation_secs;
        let mut data = Vec::with_capacity(32);
        data.extend_from_slice(b"nexora-eph-v1");
        data.extend_from_slice(&self.current_slot.to_be_bytes());
        data.extend_from_slice(&self.rotation_secs.to_be_bytes());
        for i in 0..8 {
            data.push(((self.current_slot >> (i * 8)) as u8).wrapping_add(0xA5));
        }
        self.current_id = dev_keyed_hash(&self.identity_secret, &data);
    }
}

pub fn compute_blind_target(recipient_root: &[u8], message_id: &[u8; 16]) -> [u8; BLIND_TARGET_LEN] {
    let mut data = Vec::with_capacity(32);
    data.extend_from_slice(b"nexora-blind-v1");
    data.extend_from_slice(message_id);
    dev_keyed_hash(recipient_root, &data)
}

pub fn matches_blind_target(
    candidate_root: &[u8],
    message_id: &[u8; 16],
    tag: &[u8; BLIND_TARGET_LEN],
) -> bool {
    compute_blind_target(candidate_root, message_id) == *tag
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MeshPacket {
    pub version: u8,
    pub ttl_hops: u8,
    pub flags: u8,
    pub message_id: [u8; MSG_ID_LEN],
    pub blind_target: [u8; BLIND_TARGET_LEN],
    pub expires_at: u64,
    pub payload: Vec<u8>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PacketError {
    TooShort,
    UnsupportedVersion,
    Expired,
    ZeroTtl,
}

impl MeshPacket {
    pub const FIXED_HEADER: usize = 1 + 1 + 1 + 1 + MSG_ID_LEN + BLIND_TARGET_LEN + 8;

    pub fn new(
        message_id: [u8; MSG_ID_LEN],
        blind_target: [u8; BLIND_TARGET_LEN],
        ttl_hops: u8,
        ttl_secs: u64,
        payload: Vec<u8>,
    ) -> Self {
        Self {
            version: WIRE_VERSION,
            ttl_hops,
            flags: 0,
            message_id,
            blind_target,
            expires_at: unix_now().saturating_add(ttl_secs),
            payload,
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(Self::FIXED_HEADER + self.payload.len());
        out.push(self.version);
        out.push(self.ttl_hops);
        out.push(self.flags);
        out.push(0);
        out.extend_from_slice(&self.message_id);
        out.extend_from_slice(&self.blind_target);
        out.extend_from_slice(&self.expires_at.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, PacketError> {
        if bytes.len() < Self::FIXED_HEADER {
            return Err(PacketError::TooShort);
        }
        let version = bytes[0];
        if version != WIRE_VERSION {
            return Err(PacketError::UnsupportedVersion);
        }
        let ttl_hops = bytes[1];
        let flags = bytes[2];
        let mut message_id = [0u8; MSG_ID_LEN];
        message_id.copy_from_slice(&bytes[4..20]);
        let mut blind_target = [0u8; BLIND_TARGET_LEN];
        blind_target.copy_from_slice(&bytes[20..36]);
        let expires_at = u64::from_be_bytes(bytes[36..44].try_into().unwrap());
        let payload = bytes[44..].to_vec();
        Ok(Self {
            version,
            ttl_hops,
            flags,
            message_id,
            blind_target,
            expires_at,
            payload,
        })
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
    queue: VecDeque<MeshPacket>,
    seen: HashSet<[u8; MSG_ID_LEN]>,
}

impl RelayQueue {
    pub fn new(max_size: usize) -> Self {
        Self {
            max_size: max_size.max(1),
            queue: VecDeque::new(),
            seen: HashSet::new(),
        }
    }

    pub fn enqueue(&mut self, pkt: MeshPacket, now: u64) -> bool {
        if pkt.is_expired(now) || !pkt.can_forward() || self.seen.contains(&pkt.message_id) {
            return false;
        }
        while self.queue.len() >= self.max_size {
            if let Some(old) = self.queue.pop_front() {
                self.seen.remove(&old.message_id);
            }
        }
        self.seen.insert(pkt.message_id);
        self.queue.push_back(pkt);
        true
    }

    pub fn dequeue_for_forward(&mut self, now: u64) -> Option<MeshPacket> {
        while let Some(mut pkt) = self.queue.pop_front() {
            self.seen.remove(&pkt.message_id);
            if pkt.is_expired(now) || !pkt.can_forward() {
                continue;
            }
            pkt.ttl_hops = pkt.ttl_hops.saturating_sub(1);
            return Some(pkt);
        }
        None
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    pub fn snapshot(&self) -> Vec<MeshPacket> {
        self.queue.iter().cloned().collect()
    }

    pub fn restore(&mut self, items: Vec<MeshPacket>, now: u64) {
        self.queue.clear();
        self.seen.clear();
        for p in items {
            let _ = self.enqueue(p, now);
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

#[derive(Debug, PartialEq, Eq)]
pub enum IngestResult {
    DeliveredLocally { message_id: [u8; MSG_ID_LEN] },
    QueuedForRelay,
    Dropped,
}

pub struct MeshNode {
    pub identity_secret: Vec<u8>,
    pub peer_roots: Vec<Vec<u8>>,
    pub ephemeral: EphemeralIdRotator,
    pub queue: RelayQueue,
    pub power: PowerSignals,
}

impl MeshNode {
    pub fn new(identity_secret: Vec<u8>) -> Self {
        let ephemeral = EphemeralIdRotator::new(identity_secret.clone());
        Self {
            identity_secret,
            peer_roots: Vec::new(),
            ephemeral,
            queue: RelayQueue::new(DEFAULT_MAX_QUEUE),
            power: PowerSignals::default(),
        }
    }

    pub fn add_peer_root(&mut self, root: Vec<u8>) {
        if !self.peer_roots.iter().any(|r| r == &root) {
            self.peer_roots.push(root);
        }
    }

    pub fn process_incoming_bytes(&mut self, raw: &[u8], now: u64) -> IngestResult {
        let body = match decode_frame(raw) {
            Ok(b) => b,
            Err(_) => raw,
        };
        let pkt = match MeshPacket::decode(body) {
            Ok(p) => p,
            Err(_) => return IngestResult::Dropped,
        };
        if pkt.is_expired(now) || !pkt.can_forward() {
            return IngestResult::Dropped;
        }
        for root in &self.peer_roots {
            if matches_blind_target(root, &pkt.message_id, &pkt.blind_target) {
                return IngestResult::DeliveredLocally {
                    message_id: pkt.message_id,
                };
            }
        }
        if matches_blind_target(&self.identity_secret, &pkt.message_id, &pkt.blind_target) {
            return IngestResult::DeliveredLocally {
                message_id: pkt.message_id,
            };
        }
        if !may_forward(&self.power) {
            return IngestResult::Dropped;
        }
        if self.queue.enqueue(pkt, now) {
            IngestResult::QueuedForRelay
        } else {
            IngestResult::Dropped
        }
    }

    pub fn build_outbound(
        &self,
        recipient_root: &[u8],
        message_id: [u8; MSG_ID_LEN],
        payload: Vec<u8>,
        ttl_hops: u8,
        ttl_secs: u64,
    ) -> Vec<u8> {
        let blind = compute_blind_target(recipient_root, &message_id);
        let pkt = MeshPacket::new(message_id, blind, ttl_hops, ttl_secs, payload);
        encode_frame(&pkt.encode()).expect("frame encode")
    }

    pub fn next_relay_frame(&mut self, now: u64) -> Option<Vec<u8>> {
        let pkt = self.queue.dequeue_for_forward(now)?;
        Some(encode_frame(&pkt.encode()).expect("frame encode"))
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub struct MeshSim {
    pub nodes: HashMap<&'static str, MeshNode>,
}

impl MeshSim {
    pub fn new() -> Self {
        let mut nodes = HashMap::new();
        nodes.insert("A", MeshNode::new(b"identity-secret-A-32bytes!!!!!!".to_vec()));
        nodes.insert("B", MeshNode::new(b"identity-secret-B-32bytes!!!!!!".to_vec()));
        nodes.insert("C", MeshNode::new(b"identity-secret-C-32bytes!!!!!!".to_vec()));
        let a_root = nodes["A"].identity_secret.clone();
        let c_root = nodes["C"].identity_secret.clone();
        nodes.get_mut("A").unwrap().add_peer_root(c_root);
        nodes.get_mut("C").unwrap().add_peer_root(a_root);
        Self { nodes }
    }
}

pub mod ffi {
    use super::*;
    use std::os::raw::{c_int, c_uchar, c_void};
    use std::slice;

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

    #[no_mangle]
    pub extern "C" fn nm_node_create(identity: *const c_uchar, identity_len: usize) -> *mut c_void {
        if identity.is_null() || identity_len == 0 {
            return std::ptr::null_mut();
        }
        let secret = unsafe { slice::from_raw_parts(identity, identity_len) }.to_vec();
        let node = Box::new(MeshNode::new(secret));
        Box::into_raw(node) as *mut c_void
    }

    #[no_mangle]
    pub extern "C" fn nm_node_free(handle: *mut c_void) {
        if !handle.is_null() {
            unsafe {
                drop(Box::from_raw(handle as *mut MeshNode));
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn nm_process_incoming(
        handle: *mut c_void,
        data: *const c_uchar,
        len: usize,
        now_unix: u64,
    ) -> c_int {
        if handle.is_null() || data.is_null() || len == 0 {
            return 0;
        }
        let node = unsafe { &mut *(handle as *mut MeshNode) };
        let bytes = unsafe { slice::from_raw_parts(data, len) };
        match node.process_incoming_bytes(bytes, now_unix) {
            IngestResult::DeliveredLocally { .. } => 1,
            IngestResult::QueuedForRelay => 2,
            IngestResult::Dropped => 0,
        }
    }

    #[no_mangle]
    pub extern "C" fn nm_current_ephemeral_id(handle: *mut c_void, out: *mut c_uchar) -> c_int {
        if handle.is_null() || out.is_null() {
            return -1;
        }
        let node = unsafe { &*(handle as *mut MeshNode) };
        let id = node.ephemeral.current_id();
        unsafe {
            std::ptr::copy_nonoverlapping(id.as_ptr(), out, 16);
        }
        0
    }

    #[no_mangle]
    pub extern "C" fn nm_ephemeral_tick(handle: *mut c_void, now_unix: u64) -> c_int {
        if handle.is_null() {
            return 0;
        }
        let node = unsafe { &mut *(handle as *mut MeshNode) };
        if node.ephemeral.tick(now_unix) {
            1
        } else {
            0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mid(seed: u8) -> [u8; 16] {
        let mut id = [0u8; 16];
        id[0] = seed;
        id
    }

    #[test]
    fn frame_round_trip() {
        let frame = encode_frame(b"hello mesh").unwrap();
        assert_eq!(decode_frame(&frame), Ok(&b"hello mesh"[..]));
    }

    #[test]
    fn packet_round_trip() {
        let pkt = MeshPacket::new(mid(1), mid(2), 5, 3600, b"cipher".to_vec());
        let bytes = pkt.encode();
        let decoded = MeshPacket::decode(&bytes).unwrap();
        assert_eq!(decoded.payload, b"cipher");
        assert_eq!(decoded.ttl_hops, 5);
    }

    #[test]
    fn ephemeral_rotates_on_slot_change() {
        let mut rot = EphemeralIdRotator::with_rotation_secs(b"secret-key-material-32b!!!!".to_vec(), 100);
        rot.force_rotate(1000);
        let id1 = rot.current_id();
        assert!(!rot.tick(1099));
        assert_eq!(rot.current_slot, 10);
        assert!(rot.tick(1100));
        assert_eq!(rot.current_slot, 11);
        let id2 = rot.current_id();
        assert_ne!(id1, id2, "ephemeral ID must change across rotation slots");
    }

    #[test]
    fn blind_target_only_recipient_matches() {
        let root_c = b"recipient-C-root-key-material!!!!";
        let root_other = b"someone-else-key-material!!!!!!";
        let msg = mid(9);
        let tag = compute_blind_target(root_c, &msg);
        assert!(matches_blind_target(root_c, &msg, &tag));
        assert!(!matches_blind_target(root_other, &msg, &tag));
    }

    #[test]
    fn multi_hop_a_b_c_blind_relay() {
        let mut sim = MeshSim::new();
        let now = unix_now();
        let msg = mid(42);
        let c_root = sim.nodes.get("C").unwrap().identity_secret.clone();
        let frame = {
            let a = sim.nodes.get("A").unwrap();
            a.build_outbound(&c_root, msg, b"secret text A->C".to_vec(), 5, 3600)
        };
        sim.nodes.get_mut("B").unwrap().peer_roots.clear();
        let r_b = sim.nodes.get_mut("B").unwrap().process_incoming_bytes(&frame, now);
        assert_eq!(r_b, IngestResult::QueuedForRelay, "B must only relay");
        assert_eq!(sim.nodes["B"].queue.len(), 1);
        let relay_frame = sim.nodes.get_mut("B").unwrap().next_relay_frame(now).expect("relay");
        let r_c = sim.nodes.get_mut("C").unwrap().process_incoming_bytes(&relay_frame, now);
        assert_eq!(r_c, IngestResult::DeliveredLocally { message_id: msg });
    }

    #[test]
    fn ffi_process_incoming() {
        let secret = b"ffi-test-identity-secret-32b!!";
        let handle = ffi::nm_node_create(secret.as_ptr(), secret.len());
        assert!(!handle.is_null());
        let now = unix_now();
        let junk = [0u8; 8];
        let r = ffi::nm_process_incoming(handle, junk.as_ptr(), junk.len(), now);
        assert_eq!(r, 0);
        ffi::nm_node_free(handle);
    }
}
