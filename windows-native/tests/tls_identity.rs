use codex_quota_windows_core::{TlsIdentityError, TlsIdentityKey};

#[test]
fn persisted_private_key_keeps_the_windows_public_identity_stable() {
    let generated = TlsIdentityKey::generate().expect("generate identity key");
    let fingerprint = generated
        .public_key_fingerprint_hex()
        .expect("fingerprint generated identity");
    let persisted = generated.private_key_der().to_vec();
    let restored = TlsIdentityKey::from_private_key_der(persisted).expect("restore identity key");

    assert_eq!(fingerprint.len(), 64);
    assert_eq!(
        restored
            .public_key_fingerprint_hex()
            .expect("fingerprint restored identity"),
        fingerprint,
    );
    assert!(!restored.certificate_der().expect("certificate").is_empty());
}

#[test]
fn malformed_persisted_keys_are_rejected() {
    assert_eq!(
        TlsIdentityKey::from_private_key_der(vec![1, 2, 3]).err(),
        Some(TlsIdentityError::InvalidPrivateKey),
    );
}

#[test]
fn identity_builds_a_tls_thirteen_websocket_server_configuration() {
    let identity = TlsIdentityKey::generate().expect("generate identity key");
    let config = identity
        .rustls_server_config()
        .expect("build TLS server configuration");

    assert_eq!(config.alpn_protocols, vec![b"http/1.1".to_vec()]);
}

#[cfg(windows)]
#[test]
fn windows_dpapi_protects_the_identity_for_the_current_user() {
    let generated = TlsIdentityKey::generate().expect("generate identity key");
    let fingerprint = generated
        .public_key_fingerprint_hex()
        .expect("fingerprint generated identity");

    let protected = generated
        .protect_for_current_user()
        .expect("protect identity key");
    assert_ne!(protected, generated.private_key_der());
    let restored =
        TlsIdentityKey::from_current_user_protected(&protected).expect("unprotect identity key");

    assert_eq!(
        restored
            .public_key_fingerprint_hex()
            .expect("fingerprint restored identity"),
        fingerprint,
    );
}
