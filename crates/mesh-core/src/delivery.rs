//! Authenticated next-hop storage acknowledgements and retry scheduling.
//!
//! STORE_ACK is hop-to-hop, not end-to-end delivery. The caller MUST supply a
//! distinct, authenticated pairwise hop key (at least 256 bits). Never use a
//! public key, message ciphertext, or a global application key here.
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::collections::hash_map::Entry;
use std::collections::{HashMap, HashSet, VecDeque};

type HmacSha256 = Hmac<Sha256>;
const ACK_VERSION: u8 = 1;
const ACK_DOMAIN: &[u8] = b"NexoraMesh/STORE_ACK/v1\0";
const ACK_BODY_LEN: usize = 1 + 16 + 16 + 16 + 8 + 16;
pub const STORE_ACK_LEN: usize = ACK_BODY_LEN + 32;
const MIN_HOP_KEY_BYTES: usize = 32;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoreAck {
    pub message_id: [u8; 16],
    pub from_node: [u8; 16],
    pub to_node: [u8; 16],
    pub stored_at_unix: u64,
    pub nonce: [u8; 16],
    mac: [u8; 32],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AckError {
    WeakHopKey,
    InvalidLength,
    UnsupportedVersion,
    WrongPeer,
    TimestampOutOfRange,
    InvalidMac,
    Replay,
}

impl StoreAck {
    pub fn create(
        message_id: [u8; 16],
        from_node: [u8; 16],
        to_node: [u8; 16],
        stored_at_unix: u64,
        nonce: [u8; 16],
        hop_key: &[u8],
    ) -> Result<Self, AckError> {
        if hop_key.len() < MIN_HOP_KEY_BYTES {
            return Err(AckError::WeakHopKey);
        }
        let mut ack = Self {
            message_id,
            from_node,
            to_node,
            stored_at_unix,
            nonce,
            mac: [0; 32],
        };
        let mut mac = HmacSha256::new_from_slice(hop_key).map_err(|_| AckError::WeakHopKey)?;
        mac.update(ACK_DOMAIN);
        mac.update(&ack.body());
        ack.mac.copy_from_slice(&mac.finalize().into_bytes());
        Ok(ack)
    }

    pub fn verify(
        &self,
        hop_key: &[u8],
        expected_from: [u8; 16],
        expected_to: [u8; 16],
        now_unix: u64,
        max_age_seconds: u64,
        allowed_future_skew_seconds: u64,
    ) -> Result<(), AckError> {
        if hop_key.len() < MIN_HOP_KEY_BYTES {
            return Err(AckError::WeakHopKey);
        }
        if self.from_node != expected_from || self.to_node != expected_to {
            return Err(AckError::WrongPeer);
        }
        if self.stored_at_unix > now_unix.saturating_add(allowed_future_skew_seconds)
            || now_unix.saturating_sub(self.stored_at_unix) > max_age_seconds
        {
            return Err(AckError::TimestampOutOfRange);
        }
        let mut mac = HmacSha256::new_from_slice(hop_key).map_err(|_| AckError::WeakHopKey)?;
        mac.update(ACK_DOMAIN);
        mac.update(&self.body());
        mac.verify_slice(&self.mac)
            .map_err(|_| AckError::InvalidMac)
    }

    pub fn encode(&self) -> [u8; STORE_ACK_LEN] {
        let mut out = [0u8; STORE_ACK_LEN];
        out[..ACK_BODY_LEN].copy_from_slice(&self.body());
        out[ACK_BODY_LEN..].copy_from_slice(&self.mac);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, AckError> {
        if bytes.len() != STORE_ACK_LEN {
            return Err(AckError::InvalidLength);
        }
        if bytes[0] != ACK_VERSION {
            return Err(AckError::UnsupportedVersion);
        }
        let mut message_id = [0; 16];
        message_id.copy_from_slice(&bytes[1..17]);
        let mut from_node = [0; 16];
        from_node.copy_from_slice(&bytes[17..33]);
        let mut to_node = [0; 16];
        to_node.copy_from_slice(&bytes[33..49]);
        let stored_at_unix = u64::from_be_bytes(bytes[49..57].try_into().expect("fixed length"));
        let mut nonce = [0; 16];
        nonce.copy_from_slice(&bytes[57..73]);
        let mut mac = [0; 32];
        mac.copy_from_slice(&bytes[73..105]);
        Ok(Self {
            message_id,
            from_node,
            to_node,
            stored_at_unix,
            nonce,
            mac,
        })
    }

    fn body(&self) -> [u8; ACK_BODY_LEN] {
        let mut out = [0u8; ACK_BODY_LEN];
        out[0] = ACK_VERSION;
        out[1..17].copy_from_slice(&self.message_id);
        out[17..33].copy_from_slice(&self.from_node);
        out[33..49].copy_from_slice(&self.to_node);
        out[49..57].copy_from_slice(&self.stored_at_unix.to_be_bytes());
        out[57..73].copy_from_slice(&self.nonce);
        out
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RetryPolicy {
    pub base_delay_seconds: u64,
    pub max_delay_seconds: u64,
    pub max_attempts: u8,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            base_delay_seconds: 2,
            max_delay_seconds: 300,
            max_attempts: 8,
        }
    }
}

impl RetryPolicy {
    /// Exponential delay; callers should add bounded random jitter before scheduling.
    pub fn delay_seconds(&self, completed_attempts: u8) -> u64 {
        if completed_attempts == 0 {
            return 0;
        }
        let shift = u32::from(completed_attempts.saturating_sub(1).min(63));
        self.base_delay_seconds
            .saturating_mul(1u64.checked_shl(shift).unwrap_or(u64::MAX))
            .min(self.max_delay_seconds)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DeliveryRecord {
    pub message_id: [u8; 16],
    pub attempts: u8,
    pub next_retry_at_unix: u64,
}

#[derive(Debug)]
pub struct DeliveryTracker {
    policy: RetryPolicy,
    records: HashMap<[u8; 16], DeliveryRecord>,
}

impl DeliveryTracker {
    pub fn new(policy: RetryPolicy) -> Self {
        Self {
            policy,
            records: HashMap::new(),
        }
    }

    pub fn track(&mut self, message_id: [u8; 16], now_unix: u64) -> bool {
        match self.records.entry(message_id) {
            Entry::Vacant(entry) => {
                entry.insert(DeliveryRecord {
                    message_id,
                    attempts: 0,
                    next_retry_at_unix: now_unix,
                });
                true
            }
            Entry::Occupied(_) => false,
        }
    }

    pub fn due(&self, now_unix: u64) -> Vec<DeliveryRecord> {
        self.records
            .values()
            .copied()
            .filter(|r| r.attempts < self.policy.max_attempts && r.next_retry_at_unix <= now_unix)
            .collect()
    }

    /// Record an actual send attempt; the next due time is exponential and bounded.
    pub fn mark_sent(&mut self, message_id: [u8; 16], now_unix: u64) -> bool {
        let Some(record) = self.records.get_mut(&message_id) else {
            return false;
        };
        if record.attempts >= self.policy.max_attempts {
            return false;
        }
        record.attempts = record.attempts.saturating_add(1);
        record.next_retry_at_unix =
            now_unix.saturating_add(self.policy.delay_seconds(record.attempts));
        true
    }

    pub fn acknowledge(
        &mut self,
        ack: &StoreAck,
        hop_key: &[u8],
        expected_from: [u8; 16],
        expected_to: [u8; 16],
        now_unix: u64,
        max_age_seconds: u64,
    ) -> Result<bool, AckError> {
        ack.verify(
            hop_key,
            expected_from,
            expected_to,
            now_unix,
            max_age_seconds,
            30,
        )?;
        Ok(self.records.remove(&ack.message_id).is_some())
    }

    pub fn acknowledge_authenticated(
        &mut self,
        ack: &StoreAck,
        replay: &mut AckReplayWindow,
        hop_key: &[u8],
        expected_from: [u8; 16],
        expected_to: [u8; 16],
        now_unix: u64,
        max_age_seconds: u64,
    ) -> Result<bool, AckError> {
        replay.verify_and_accept(
            ack,
            hop_key,
            expected_from,
            expected_to,
            now_unix,
            max_age_seconds,
        )?;
        Ok(self.records.remove(&ack.message_id).is_some())
    }

    pub fn snapshot(&self) -> Vec<DeliveryRecord> {
        self.records.values().copied().collect()
    }

    pub fn restore(&mut self, records: &[DeliveryRecord]) {
        for record in records.iter().take(4096) {
            self.records.entry(record.message_id).or_insert(*record);
        }
    }
}

/// Bounded nonce replay window for authenticated STORE_ACK frames.
#[derive(Debug)]
pub struct AckReplayWindow {
    capacity: usize,
    seen: HashSet<([u8; 16], [u8; 16])>,
    order: VecDeque<([u8; 16], [u8; 16])>,
}

impl AckReplayWindow {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            seen: HashSet::new(),
            order: VecDeque::new(),
        }
    }
    pub fn verify_and_accept(
        &mut self,
        ack: &StoreAck,
        hop_key: &[u8],
        expected_from: [u8; 16],
        expected_to: [u8; 16],
        now_unix: u64,
        max_age_seconds: u64,
    ) -> Result<(), AckError> {
        ack.verify(
            hop_key,
            expected_from,
            expected_to,
            now_unix,
            max_age_seconds,
            30,
        )?;
        let key = (ack.from_node, ack.nonce);
        if !self.seen.insert(key) {
            return Err(AckError::Replay);
        }
        self.order.push_back(key);
        while self.order.len() > self.capacity {
            if let Some(old) = self.order.pop_front() {
                self.seen.remove(&old);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::relay::{Envelope, RelayDecision, RelayQueue};

    const KEY_AB: [u8; 32] = [0xA1; 32];
    const KEY_BC: [u8; 32] = [0xB2; 32];
    const A: [u8; 16] = [1; 16];
    const B: [u8; 16] = [2; 16];
    const C: [u8; 16] = [3; 16];

    #[test]
    fn store_ack_round_trip_authenticates_identity_and_payload() {
        let ack = StoreAck::create([9; 16], B, A, 100, [7; 16], &KEY_AB).unwrap();
        let decoded = StoreAck::decode(&ack.encode()).unwrap();
        assert_eq!(decoded, ack);
        assert_eq!(decoded.verify(&KEY_AB, B, A, 101, 30, 5), Ok(()));
        assert_eq!(
            decoded.verify(&[0xCC; 32], B, A, 101, 30, 5),
            Err(AckError::InvalidMac)
        );
        assert_eq!(
            decoded.verify(&KEY_AB, C, A, 101, 30, 5),
            Err(AckError::WrongPeer)
        );
        assert_eq!(
            decoded.verify(&KEY_AB, B, A, 1000, 30, 5),
            Err(AckError::TimestampOutOfRange)
        );
    }

    #[test]
    fn delivery_retry_uses_bounded_exponential_backoff_and_ack_clears_it() {
        let mut tracker = DeliveryTracker::new(RetryPolicy::default());
        assert!(tracker.track([8; 16], 10));
        assert_eq!(tracker.due(10).len(), 1);
        assert!(tracker.mark_sent([8; 16], 10));
        assert!(tracker.due(11).is_empty());
        assert_eq!(tracker.due(12).len(), 1);
        tracker.mark_sent([8; 16], 12);
        assert!(tracker.due(15).is_empty());
        assert_eq!(tracker.due(16).len(), 1);
        let ack = StoreAck::create([8; 16], B, A, 16, [1; 16], &KEY_AB).unwrap();
        let mut replay = AckReplayWindow::new(16);
        assert_eq!(
            tracker.acknowledge_authenticated(&ack, &mut replay, &KEY_AB, B, A, 16, 30),
            Ok(true)
        );
        assert!(tracker.snapshot().is_empty());
    }

    #[test]
    fn ack_replay_window_rejects_duplicate_nonce() {
        let ack = StoreAck::create([1; 16], B, A, 1, [4; 16], &KEY_AB).unwrap();
        let mut window = AckReplayWindow::new(8);
        assert_eq!(window.verify_and_accept(&ack, &KEY_AB, B, A, 1, 30), Ok(()));
        assert_eq!(
            window.verify_and_accept(&ack, &KEY_AB, B, A, 1, 30),
            Err(AckError::Replay)
        );
    }

    #[test]
    fn real_three_node_store_forward_requires_each_hop_store_ack() {
        let original = Envelope {
            message_id: [42; 16],
            destination_token: C,
            hops_remaining: 3,
            expires_at_unix: 500,
            ciphertext: b"opaque E2EE ciphertext".to_vec(),
        };
        let mut b_store = RelayQueue::new(8, 32);
        b_store.set_relay_enabled(true);
        assert_eq!(
            b_store.enqueue(original.clone(), 10),
            RelayDecision::Forward
        );

        let ack_ab = StoreAck::create(original.message_id, B, A, 10, [11; 16], &KEY_AB).unwrap();
        let mut a_delivery = DeliveryTracker::new(RetryPolicy::default());
        a_delivery.track(original.message_id, 10);
        assert_eq!(
            a_delivery.acknowledge(&ack_ab, &KEY_AB, B, A, 10, 60),
            Ok(true)
        );
        assert_eq!(b_store.len(), 1);

        let to_c = b_store.next_for_forwarding(11).unwrap();
        assert_eq!(to_c.hops_remaining, 2);
        let mut c_store = RelayQueue::new(8, 32);
        c_store.set_relay_enabled(true);
        assert_eq!(c_store.enqueue(to_c, 11), RelayDecision::Forward);
        let ack_bc = StoreAck::create(original.message_id, C, B, 11, [12; 16], &KEY_BC).unwrap();
        assert_eq!(ack_bc.verify(&KEY_BC, C, B, 11, 60, 30), Ok(()));
        assert_eq!(c_store.len(), 1);
        assert!(b_store.acknowledge_hop(original.message_id));
        assert_eq!(b_store.len(), 0);
        assert_eq!(
            c_store.pop_next(12).unwrap().message_id,
            original.message_id
        );
    }
}
