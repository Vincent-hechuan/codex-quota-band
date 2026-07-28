use codex_quota_windows_core::{PairingError, PairingManager};

#[test]
fn pairing_a_replacement_phone_revokes_the_previous_phone() {
    let mut pairing = PairingManager::new([7; 32]);

    let first_offer = pairing.begin(1_000).expect("first pairing offer");
    let first_token = pairing
        .complete(&first_offer.code, 1_001)
        .expect("first phone paired");
    assert!(pairing.authenticate(first_token.expose()));

    let replacement_offer = pairing.begin(2_000).expect("replacement offer");
    let replacement_token = pairing
        .complete(&replacement_offer.code, 2_001)
        .expect("replacement phone paired");

    assert!(!pairing.authenticate(first_token.expose()));
    assert!(pairing.authenticate(replacement_token.expose()));
}

#[test]
fn pairing_offer_expires_after_five_minutes() {
    let mut pairing = PairingManager::new([8; 32]);
    let offer = pairing.begin(10_000).expect("pairing offer");

    assert_eq!(
        pairing.complete(&offer.code, 310_001).err(),
        Some(PairingError::Expired)
    );
    assert_eq!(
        pairing.complete(&offer.code, 310_002).err(),
        Some(PairingError::NoActiveOffer)
    );
}

#[test]
fn three_wrong_codes_invalidate_the_pairing_offer() {
    let mut pairing = PairingManager::new([9; 32]);
    let offer = pairing.begin(20_000).expect("pairing offer");
    let wrong_code = if offer.code == "000000" {
        "999999"
    } else {
        "000000"
    };

    for now_ms in [20_001, 20_002, 20_003] {
        assert_eq!(
            pairing.complete(wrong_code, now_ms).err(),
            Some(PairingError::InvalidCode)
        );
    }
    assert_eq!(
        pairing.complete(&offer.code, 20_004).err(),
        Some(PairingError::NoActiveOffer)
    );
}

#[test]
fn qr_offer_contains_only_temporary_pairing_material_and_the_public_identity() {
    let mut pairing = PairingManager::new([0xab; 32]);
    let offer = pairing
        .begin(20_000)
        .expect("pairing offer")
        .to_qr_offer(vec!["wss://192.168.1.42:17322/pair".to_string()])
        .expect("valid private endpoint");

    let json = serde_json::to_value(offer).expect("serializable QR offer");
    assert_eq!(json["protocolVersion"], 1);
    assert_eq!(json["type"], "pairing_offer");
    assert_eq!(json["computerFingerprint"], "ab".repeat(32));
    assert_eq!(json["endpoints"][0], "wss://192.168.1.42:17322/pair");
    assert!(json.get("token").is_none());
    assert!(json.get("privateKey").is_none());
}

#[test]
fn qr_offer_rejects_plaintext_public_and_duplicate_endpoints() {
    let mut pairing = PairingManager::new([0xab; 32]);
    let offer = pairing.begin(20_000).expect("pairing offer");

    for endpoints in [
        vec!["ws://192.168.1.42:17322/pair".to_string()],
        vec!["wss://8.8.8.8:17322/pair".to_string()],
        vec![
            "wss://192.168.1.42:17322/pair".to_string(),
            "wss://192.168.1.42:17322/pair".to_string(),
        ],
    ] {
        assert_eq!(
            offer.to_qr_offer(endpoints).err(),
            Some(PairingError::InvalidEndpoint)
        );
    }
}
