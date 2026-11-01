//! Pairwise BLE-hop key derivation from an authenticated ECDH secret.
use hmac::{Hmac, Mac};
use sha2::Sha256;

const SALT: &[u8] = b"NexoraMesh/hop-key/extract/v1";
const INFO: &[u8] = b"NexoraMesh/BLE-STORE-ACK/v1";

/// Derive the same key on both peers, bound to stable peer IDs and an epoch.
/// Never pass an end-to-end message key here.
pub fn derive_hop_key(
    shared_secret: &[u8],
    peer_a: &[u8; 16],
    peer_b: &[u8; 16],
    epoch: u64,
) -> Result<[u8; 32], ()> {
    if shared_secret.len() < 32 || peer_a == peer_b {
        return Err(());
    }
    let mut extract = Hmac::<Sha256>::new_from_slice(SALT).map_err(|_| ())?;
    extract.update(shared_secret);
    let prk = extract.finalize().into_bytes();
    let (first, second) = if peer_a < peer_b {
        (peer_a, peer_b)
    } else {
        (peer_b, peer_a)
    };
    let mut expand = Hmac::<Sha256>::new_from_slice(&prk).map_err(|_| ())?;
    expand.update(INFO);
    expand.update(first);
    expand.update(second);
    expand.update(&epoch.to_be_bytes());
    expand.update(&[1]);
    let output = expand.finalize().into_bytes();
    let mut key = [0u8; 32];
    key.copy_from_slice(&output);
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn derivation_is_symmetric() {
        let a = [1u8; 16];
        let b = [2u8; 16];
        let secret = [9u8; 32];
        assert_eq!(
            derive_hop_key(&secret, &a, &b, 7),
            derive_hop_key(&secret, &b, &a, 7)
        );
    }
    #[test]
    fn peer_and_epoch_are_separated() {
        let secret = [9u8; 32];
        let a = [1u8; 16];
        let b = [2u8; 16];
        let c = [3u8; 16];
        let base = derive_hop_key(&secret, &a, &b, 7).unwrap();
        assert_ne!(base, derive_hop_key(&secret, &a, &b, 8).unwrap());
        assert_ne!(base, derive_hop_key(&secret, &a, &c, 7).unwrap());
    }
    #[test]
    fn rejects_weak_secret_and_same_peer() {
        assert!(derive_hop_key(&[1u8; 31], &[1; 16], &[2; 16], 1).is_err());
        assert!(derive_hop_key(&[1u8; 32], &[1; 16], &[1; 16], 1).is_err());
    }
}
