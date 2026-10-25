import unittest

from tools.codec_vectors import FrameError, decode_frame, encode_frame


class FrameCodecTests(unittest.TestCase):
    def test_round_trip(self):
        self.assertEqual(decode_frame(encode_frame(b"hello")), b"hello")

    def test_empty_payload(self):
        self.assertEqual(decode_frame(encode_frame(b"")), b"")

    def test_short_header(self):
        with self.assertRaises(FrameError):
            decode_frame(b"\x00")

    def test_truncated_payload(self):
        with self.assertRaises(FrameError):
            decode_frame(b"\x00\x00\x00\x03x")

    def test_oversized_length(self):
        with self.assertRaises(FrameError):
            decode_frame(b"\x00\x10\x00\x01")

    def test_trailing_bytes(self):
        with self.assertRaises(FrameError):
            decode_frame(b"\x00\x00\x00\x01ab")

    def test_encode_limit(self):
        with self.assertRaises(FrameError):
            encode_frame(b"x" * (1024 * 1024 + 1))


if __name__ == "__main__":
    unittest.main()
