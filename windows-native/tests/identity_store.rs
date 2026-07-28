use codex_quota_windows_core::storage::{IdentityStoreError, TlsIdentityStore};
use std::fs;

#[test]
fn identity_store_round_trips_the_same_public_identity() {
    let path = std::env::temp_dir().join(format!(
        "codex-quota-identity-{}-{}.bin",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let store = TlsIdentityStore::new(&path);
    let first = store.load_or_create().expect("create identity");
    let fingerprint = first.public_key_fingerprint_hex().expect("fingerprint");
    let restored = store.load_or_create().expect("restore identity");
    assert_eq!(
        restored
            .public_key_fingerprint_hex()
            .expect("restored fingerprint"),
        fingerprint
    );
    let raw = fs::read(&path).expect("identity file");
    assert!(raw.starts_with(b"CQTI\x01"));
    let _ = fs::remove_file(path);
}

#[test]
fn identity_store_rejects_corruption_without_rotating_the_identity() {
    let path = std::env::temp_dir().join(format!(
        "codex-quota-identity-corrupt-{}",
        std::process::id()
    ));
    let _ = fs::remove_file(&path);
    fs::write(&path, b"bad").expect("corrupt identity file");
    let error = match TlsIdentityStore::new(&path).load_or_create() {
        Ok(_) => panic!("corrupt identity unexpectedly loaded"),
        Err(error) => error,
    };
    assert!(matches!(error, IdentityStoreError::InvalidFile));
    let _ = fs::remove_file(path);
}
