//! Versioned binary wire format for store-and-forward envelopes.
//!
//! The wire codec does not encrypt or authenticate data. The ciphertext must already
//! be end-to-end encrypted and authenticated by the session layer before encoding.
use crate::relay::{Envelope, MAX_CIPHERTEXT_BYTES, MAX_HOPS};

const MAGIC: &[u8; 2] = b"NM";
const VERSION: u8 = 1;
const HEADER_LEN: usize = 2 + 1 + 16 + 16 + 1 + 8 + 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WireError {
    HeaderTooShort,
    InvalidMagic,
    UnsupportedVersion,
    InvalidHopBudget,
    EmptyCiphertext,
    CiphertextTooLarge,
    TruncatedCiphertext,
    TrailingBytes,
}

pub fn encode_envelope(envelope: &Envelope) -> Result<Vec<u8>, WireError> {
    if envelope.hops_remaining == 0 || envelope.hops_remaining > MAX_HOPS {
        return Err(WireError::InvalidHopBudget);
    }
    if envelope.ciphertext.is_empty() {
        return Err(WireError::EmptyCiphertext);
    }
    if envelope.ciphertext.len() > MAX_CIPHERTEXT_BYTES {
        return Err(WireError::CiphertextTooLarge);
    }

    let mut output = Vec::with_capacity(HEADER_LEN + envelope.ciphertext.len());
    output.extend_from_slice(MAGIC);
    output.push(VERSION);
    output.extend_from_slice(&envelope.message_id);
    output.extend_from_slice(&envelope.destination_token);
    output.push(envelope.hops_remaining);
    output.extend_from_slice(&envelope.expires_at_unix.to_be_bytes());
    output.extend_from_slice(&(envelope.ciphertext.len() as u32).to_be_bytes());
    output.extend_from_slice(&envelope.ciphertext);
    Ok(output)
}

pub fn decode_envelope(bytes: &[u8]) -> Result<Envelope, WireError> {
    if bytes.len() < HEADER_LEN {
        return Err(WireError::HeaderTooShort);
    }
    if &bytes[..2] != MAGIC {
        return Err(WireError::InvalidMagic);
    }
    if bytes[2] != VERSION {
        return Err(WireError::UnsupportedVersion);
    }

    let message_id = bytes[3..19].try_into().expect("fixed header checked");
    let destination_token = bytes[19..35].try_into().expect("fixed header checked");
    let hops_remaining = bytes[35];
    if hops_remaining == 0 || hops_remaining > MAX_HOPS {
        return Err(WireError::InvalidHopBudget);
    }
    let expires_at_unix = u64::from_be_bytes(bytes[36..44].try_into().expect("fixed header checked"));
    let ciphertext_len = u32::from_be_bytes(bytes[44..48].try_into().expect("fixed header checked")) as usize;
    if ciphertext_len == 0 {
        return Err(WireError::EmptyCiphertext);
    }
    if ciphertext_len > MAX_CIPHERTEXT_BYTES {
        return Err(WireError::CiphertextTooLarge);
    }
    let expected = HEADER_LEN + ciphertext_len;
    if bytes.len() < expected {
        return Err(WireError::TruncatedCiphertext);
    }
    if bytes.len() > expected {
        return Err(WireError::TrailingBytes);
    }

    Ok(Envelope {
        message_id,
        destination_token,
        hops_remaining,
        expires_at_unix,
        ciphertext: bytes[HEADER_LEN..expected].to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Envelope {
        Envelope {
            message_id: [0x11; 16],
            destination_token: [0x22; 16],
            hops_remaining: 5,
            expires_at_unix: 1_800_000_000,
            ciphertext: b"already-encrypted-bytes".to_vec(),
        }
    }

    #[test]
    fn envelope_round_trips_without_changing_ciphertext() {
        let original = sample();
        let encoded = encode_envelope(&original).unwrap();
        let decoded = decode_envelope(&encoded).unwrap();
        assert_eq!(decoded, original);
    }

    #[test]
    fn rejects_invalid_magic_and_version() {
        let mut bytes = encode_envelope(&sample()).unwrap();
        bytes[0] = b'X';
        assert_eq!(decode_envelope(&bytes), Err(WireError::InvalidMagic));
        let mut bytes = encode_envelope(&sample()).unwrap();
        bytes[2] = 2;
        assert_eq!(decode_envelope(&bytes), Err(WireError::UnsupportedVersion));
    }

    #[test]
    fn rejects_zero_and_excessive_hops() {
        let mut bytes = encode_envelope(&sample()).unwrap();
        bytes[35] = 0;
        assert_eq!(decode_envelope(&bytes), Err(WireError::InvalidHopBudget));
        bytes[35] = MAX_HOPS + 1;
        assert_eq!(decode_envelope(&bytes), Err(WireError::InvalidHopBudget));
    }

    #[test]
    fn rejects_truncated_and_trailing_bytes() {
        let bytes = encode_envelope(&sample()).unwrap();
        assert_eq!(decode_envelope(&bytes[..bytes.len() - 1]), Err(WireError::TruncatedCiphertext));
        let mut trailing = bytes;
        trailing.push(0);
        assert_eq!(decode_envelope(&trailing), Err(WireError::TrailingBytes));
    }

    #[test]
    fn rejects_empty_ciphertext() {
        let mut bytes = encode_envelope(&sample()).unwrap();
        bytes[44..48].copy_from_slice(&0u32.to_be_bytes());
        assert_eq!(decode_envelope(&bytes[..HEADER_LEN]), Err(WireError::EmptyCiphertext));
    }
}
