"""Reference vectors for the experimental v1 key-derivation contract.

These vectors specify the cross-platform wire contract; they do not prove that the
Swift and Android runtimes execute these operations correctly. Native tests and
physical-device interoperability checks are still required.
"""
import hashlib
import hmac
import unittest


class SessionKeyVectors(unittest.TestCase):
    def test_v1_hmac_sha256_kdf_vector(self):
        shared_secret = bytes(range(32))
        kdf_key = b"NexoraMesh/session-key/v1"
        expected = "f686564b5419341dd9eaa8bcfa472c607e046069d08e66243c97a0fa180d0162"
        actual = hmac.new(kdf_key, shared_secret, hashlib.sha256).hexdigest()
        self.assertEqual(expected, actual)

    def test_fingerprint_vector(self):
        public_key_der = bytes.fromhex("3000")
        expected = "E4:F6:0D:0A:A6:D7:F3:D3:B6:A6:49:4B:1C:86:1B:99:F6:49:C6:F9"
        rendered = ":".join(f"{byte:02X}" for byte in hashlib.sha256(public_key_der).digest()[:20])
        self.assertEqual(expected, rendered)


if __name__ == "__main__":
    unittest.main()
