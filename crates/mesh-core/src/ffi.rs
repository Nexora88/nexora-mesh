//! Narrow C ABI for Android JNI and iOS Swift/Clang callers.
//! Caller owns all buffers and must pass valid readable/writable memory.
//! These functions validate/forward opaque encrypted envelopes; they do not encrypt,
//! authenticate peers, or prove a QR fingerprint.
use crate::wire::{decode_envelope, encode_envelope, WireError};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::relay::Envelope;

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
}
