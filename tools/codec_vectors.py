"""Reference framing codec for tests; not encryption or authentication."""
from __future__ import annotations

HEADER_LEN = 4
MAX_FRAME_LEN = 1024 * 1024


class FrameError(ValueError):
    pass


def encode_frame(payload: bytes) -> bytes:
    if len(payload) > MAX_FRAME_LEN:
        raise FrameError("payload too large")
    return len(payload).to_bytes(HEADER_LEN, "big") + payload


def decode_frame(frame: bytes) -> bytes:
    if len(frame) < HEADER_LEN:
        raise FrameError("header too short")
    size = int.from_bytes(frame[:HEADER_LEN], "big")
    if size > MAX_FRAME_LEN:
        raise FrameError("payload too large")
    expected = HEADER_LEN + size
    if len(frame) < expected:
        raise FrameError("payload truncated")
    if len(frame) > expected:
        raise FrameError("trailing bytes")
    return frame[HEADER_LEN:expected]
