//! Narrow C ABI for Android JNI and iOS Swift/Clang callers.
//! Caller owns all buffers and must pass valid readable/writable memory.
//! These functions validate/forward opaque encrypted envelopes; they do not encrypt,
//! authenticate peers, or prove a QR fingerprint.
use crate::hop_key::derive_hop_key;
use crate::wire::{decode_envelope, encode_envelope, WireError};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

/// Derive a peer- and epoch-bound hop key from authenticated pairwise ECDH output.
/// # Safety
/// Pointers must reference readable buffers of the stated lengths; output must be writable.
#[no_mangle]
pub unsafe extern "C" fn nm_derive_hop_key(
    shared_secret: *const u8,
    secret_len: usize,
    peer_a: *const u8,
    peer_b: *const u8,
    epoch: u64,
    output: *mut u8,
    output_capacity: usize,
) -> isize {
    if shared_secret.is_null()
        || peer_a.is_null()
        || peer_b.is_null()
        || output.is_null()
        || output_capacity < 32
        || secret_len > 4096
    {
        return -30;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let secret = slice::from_raw_parts(shared_secret, secret_len);
        let mut a = [0u8; 16];
        let mut b = [0u8; 16];
        a.copy_from_slice(slice::from_raw_parts(peer_a, 16));
        b.copy_from_slice(slice::from_raw_parts(peer_b, 16));
        match derive_hop_key(secret, &a, &b, epoch) {
            Ok(key) => {
                slice::from_raw_parts_mut(output, 32).copy_from_slice(&key);
                32
            }
            Err(_) => -31,
        }
    }))
    .unwrap_or(-127)
}

fn error_code(error: WireError) -> i32 {
    match error {
        WireError::HeaderTooShort => -1,
        WireError::InvalidMagic => -2,
        WireError::UnsupportedVersion => -3,
        WireError::InvalidHopBudget => -4,
        WireError::EmptyCiphertext => -5,
        WireError::CiphertextTooLarge => -6,
        WireError::TruncatedCiphertext => -7,
        WireError::TrailingBytes => -8,
    }
}

/// Validate a complete v1 envelope. Returns 0 if valid, otherwise a stable negative code.
///
/// # Safety
/// input must point to input_len readable bytes, or be null only when input_len == 0.
#[no_mangle]
pub unsafe extern "C" fn nm_wire_validate(input: *const u8, input_len: usize) -> i32 {
    if input.is_null() || input_len == 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = slice::from_raw_parts(input, input_len);
        match decode_envelope(bytes) {
            Ok(_) => 0,
            Err(error) => error_code(error),
        }
    }))
    .unwrap_or(-127)
}

/// Copy an envelope with its hop budget decremented by one.
/// Returns output byte count, a negative error, or required capacity if too small.
///
/// # Safety
/// input and output must reference valid buffers of the stated lengths and must not overlap.
#[no_mangle]
pub unsafe extern "C" fn nm_wire_forward(
    input: *const u8,
    input_len: usize,
    output: *mut u8,
    output_capacity: usize,
    now_unix: u64,
) -> isize {
    if input.is_null() || input_len == 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = slice::from_raw_parts(input, input_len);
        let envelope = match decode_envelope(bytes) {
            Ok(value) => value,
            Err(error) => return error_code(error) as isize,
        };
        if envelope.expires_at_unix <= now_unix {
            return -9;
        }
        let forwarded = match envelope.forwarded(now_unix) {
            Some(value) => value,
            None => return -4,
        };
        let encoded = match encode_envelope(&forwarded) {
            Ok(value) => value,
            Err(error) => return error_code(error) as isize,
        };
        if output_capacity < encoded.len() {
            return encoded.len() as isize;
        }
        if output.is_null() {
            return -10;
        }
        slice::from_raw_parts_mut(output, encoded.len()).copy_from_slice(&encoded);
        encoded.len() as isize
    }))
    .unwrap_or(-127)
}

/// Return a recommended scan interval in seconds from host battery/activity signals.
/// The OS remains authoritative; this function does not schedule background work.
///
/// # Safety
/// No pointers are accepted; all boolean-like parameters must be 0 or 1.
#[no_mangle]
pub extern "C" fn nm_relay_scan_interval_seconds(
    battery_percent: u8,
    charging: u8,
    low_power_mode: u8,
    app_foreground: u8,
    recently_connected: u8,
    urgent_pending: u8,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        let state = crate::relay::RelayPowerState {
            battery_percent: battery_percent.min(100),
            charging: charging != 0,
            low_power_mode: low_power_mode != 0,
            app_foreground: app_foreground != 0,
            recently_connected: recently_connected != 0,
            urgent_pending: urgent_pending != 0,
        };
        crate::relay::SmartRelayPolicy::default().recommended_scan_interval_seconds(state)
    }))
    .unwrap_or(300)
}

/// Return 1 only when relaying is explicitly opted into and power policy permits it.
#[no_mangle]
pub extern "C" fn nm_relay_may_forward(
    opted_in: u8,
    battery_percent: u8,
    charging: u8,
    low_power_mode: u8,
    app_foreground: u8,
) -> u8 {
    catch_unwind(AssertUnwindSafe(|| {
        let state = crate::relay::RelayPowerState {
            battery_percent: battery_percent.min(100),
            charging: charging != 0,
            low_power_mode: low_power_mode != 0,
            app_foreground: app_foreground != 0,
            recently_connected: false,
            urgent_pending: false,
        };
        u8::from(crate::relay::SmartRelayPolicy::default().may_relay(state, opted_in != 0))
    }))
    .unwrap_or(0)
}

/// Create a versioned HMAC-SHA256 STORE_ACK using a caller-provided pairwise hop key.
/// Returns encoded byte count, a negative error, or required capacity when too small.
///
/// # Safety
/// All fixed-size inputs must reference 16 readable bytes. key must reference key_len
/// readable bytes. output must reference output_capacity writable bytes.
#[no_mangle]
pub unsafe extern "C" fn nm_store_ack_create(
    message_id: *const u8,
    from_node: *const u8,
    to_node: *const u8,
    stored_at_unix: u64,
    nonce: *const u8,
    hop_key: *const u8,
    key_len: usize,
    output: *mut u8,
    output_capacity: usize,
) -> isize {
    if message_id.is_null()
        || from_node.is_null()
        || to_node.is_null()
        || nonce.is_null()
        || hop_key.is_null()
    {
        return -20;
    }
    if key_len < 32 {
        return -21;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let mut id = [0u8; 16];
        id.copy_from_slice(slice::from_raw_parts(message_id, 16));
        let mut from = [0u8; 16];
        from.copy_from_slice(slice::from_raw_parts(from_node, 16));
        let mut to = [0u8; 16];
        to.copy_from_slice(slice::from_raw_parts(to_node, 16));
        let mut nonce_bytes = [0u8; 16];
        nonce_bytes.copy_from_slice(slice::from_raw_parts(nonce, 16));
        let key = slice::from_raw_parts(hop_key, key_len);
        let ack =
            match crate::delivery::StoreAck::create(id, from, to, stored_at_unix, nonce_bytes, key)
            {
                Ok(value) => value.encode(),
                Err(_) => return -21,
            };
        if output_capacity < ack.len() {
            return ack.len() as isize;
        }
        if output.is_null() {
            return -22;
        }
        slice::from_raw_parts_mut(output, ack.len()).copy_from_slice(&ack);
        ack.len() as isize
    }))
    .unwrap_or(-127)
}

/// Verify a STORE_ACK HMAC, expected peer identities, and timestamp bounds.
/// Returns 0 on success or a stable negative error code.
///
/// # Safety
/// input must reference 105 readable bytes; peer IDs each reference 16 bytes;
/// hop_key must reference key_len readable bytes.
#[no_mangle]
pub unsafe extern "C" fn nm_store_ack_verify(
    input: *const u8,
    input_len: usize,
    hop_key: *const u8,
    key_len: usize,
    expected_from: *const u8,
    expected_to: *const u8,
    now_unix: u64,
    max_age_seconds: u64,
) -> i32 {
    if input.is_null() || hop_key.is_null() || expected_from.is_null() || expected_to.is_null() {
        return -23;
    }
    if key_len < 32 {
        return -21;
    }
    if input_len != crate::delivery::STORE_ACK_LEN {
        return -23;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = slice::from_raw_parts(input, input_len);
        let ack = match crate::delivery::StoreAck::decode(bytes) {
            Ok(value) => value,
            Err(crate::delivery::AckError::UnsupportedVersion) => return -24,
            Err(_) => return -23,
        };
        let mut from = [0u8; 16];
        from.copy_from_slice(slice::from_raw_parts(expected_from, 16));
        let mut to = [0u8; 16];
        to.copy_from_slice(slice::from_raw_parts(expected_to, 16));
        match ack.verify(
            slice::from_raw_parts(hop_key, key_len),
            from,
            to,
            now_unix,
            max_age_seconds,
            30,
        ) {
            Ok(()) => 0,
            Err(crate::delivery::AckError::WeakHopKey) => -21,
            Err(crate::delivery::AckError::WrongPeer) => -25,
            Err(crate::delivery::AckError::TimestampOutOfRange) => -26,
            Err(crate::delivery::AckError::InvalidMac) => -27,
            Err(_) => -23,
        }
    }))
    .unwrap_or(-127)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::relay::Envelope;

    #[test]
    fn power_policy_ffi_sleeps_on_low_battery_and_needs_opt_in() {
        assert_eq!(nm_relay_scan_interval_seconds(5, 0, 1, 0, 0, 0), 300);
        assert_eq!(nm_relay_may_forward(0, 90, 0, 0, 1), 0);
        assert_eq!(nm_relay_may_forward(1, 90, 0, 0, 1), 1);
        assert_eq!(nm_relay_may_forward(1, 5, 0, 1, 0), 0);
    }

    #[test]
    fn ffi_validate_and_forward_round_trip() {
        let envelope = Envelope {
            message_id: [1; 16],
            destination_token: [2; 16],
            hops_remaining: 3,
            expires_at_unix: 500,
            ciphertext: b"opaque-ciphertext".to_vec(),
        };
        let encoded = encode_envelope(&envelope).unwrap();
        assert_eq!(
            unsafe { nm_wire_validate(encoded.as_ptr(), encoded.len()) },
            0
        );
        let mut output = vec![0u8; encoded.len()];
        let written = unsafe {
            nm_wire_forward(
                encoded.as_ptr(),
                encoded.len(),
                output.as_mut_ptr(),
                output.len(),
                10,
            )
        };
        assert_eq!(written, encoded.len() as isize);
        let forwarded = decode_envelope(&output).unwrap();
        assert_eq!(forwarded.hops_remaining, 2);
        assert_eq!(forwarded.ciphertext, envelope.ciphertext);
    }

    #[test]
    fn ffi_rejects_expired_envelope() {
        let envelope = Envelope {
            message_id: [1; 16],
            destination_token: [2; 16],
            hops_remaining: 3,
            expires_at_unix: 5,
            ciphertext: b"ciphertext".to_vec(),
        };
        let encoded = encode_envelope(&envelope).unwrap();
        let mut output = vec![0u8; encoded.len()];
        assert_eq!(
            unsafe {
                nm_wire_forward(
                    encoded.as_ptr(),
                    encoded.len(),
                    output.as_mut_ptr(),
                    output.len(),
                    5,
                )
            },
            -9
        );
    }

    #[test]
    fn ffi_store_ack_create_and_verify_checks_pairwise_key_and_peer_ids() {
        let id = [7u8; 16];
        let from = [8u8; 16];
        let to = [9u8; 16];
        let nonce = [10u8; 16];
        let key = [11u8; 32];
        let mut ack = [0u8; crate::delivery::STORE_ACK_LEN];
        let written = unsafe {
            nm_store_ack_create(
                id.as_ptr(),
                from.as_ptr(),
                to.as_ptr(),
                100,
                nonce.as_ptr(),
                key.as_ptr(),
                key.len(),
                ack.as_mut_ptr(),
                ack.len(),
            )
        };
        assert_eq!(written, crate::delivery::STORE_ACK_LEN as isize);
        assert_eq!(
            unsafe {
                nm_store_ack_verify(
                    ack.as_ptr(),
                    ack.len(),
                    key.as_ptr(),
                    key.len(),
                    from.as_ptr(),
                    to.as_ptr(),
                    101,
                    30,
                )
            },
            0
        );
        assert_eq!(
            unsafe {
                nm_store_ack_verify(
                    ack.as_ptr(),
                    ack.len(),
                    key.as_ptr(),
                    key.len(),
                    to.as_ptr(),
                    from.as_ptr(),
                    101,
                    30,
                )
            },
            -25
        );
    }
}
