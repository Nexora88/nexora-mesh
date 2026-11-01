//! Transport-independent framing primitives. Framing is not encryption or authentication.
pub mod delivery;
pub mod ffi;
pub mod relay;
pub mod wire;

use std::convert::TryInto;

pub const HEADER_LEN: usize = 4;
pub const MAX_FRAME_LEN: usize = 1024 * 1024;

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
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
}
