use codex_quota_windows_core::pairing_discovery::PairingDiscoveryAnnouncement;
use std::net::UdpSocket;
use std::time::Duration;

#[test]
fn discovery_omits_pairing_code_and_formats_the_security_code() {
    let announcement = PairingDiscoveryAnnouncement::new(
        format!("{}{}", "a7f219c4", "ab".repeat(28)),
        vec!["wss://192.168.1.42:17322/pair".to_string()],
        1_784_880_300_000,
    )
    .expect("valid discovery");

    let payload = announcement.to_json().expect("serializable discovery");
    assert_eq!(announcement.security_code(), "A7F2-19C4");
    assert!(!payload.contains("123456"));
    assert!(!payload.contains("\"code\""));
    assert!(!payload.contains("token"));
    assert!(announcement.is_active_at(1_784_880_299_999));
    assert!(!announcement.is_active_at(1_784_880_300_001));
}

#[test]
fn discovery_packet_completes_one_udp_round_trip() {
    let receiver = UdpSocket::bind("127.0.0.1:0").expect("bind receiver");
    receiver
        .set_read_timeout(Some(Duration::from_secs(1)))
        .expect("set timeout");
    let announcement = PairingDiscoveryAnnouncement::new(
        "ab".repeat(32),
        vec!["wss://192.168.1.42:17322/pair".to_string()],
        i64::MAX,
    )
    .expect("valid discovery");

    announcement
        .send_once_to(receiver.local_addr().expect("receiver address"))
        .expect("send discovery");

    let mut buffer = [0_u8; 4096];
    let (size, _) = receiver.recv_from(&mut buffer).expect("receive discovery");
    let payload = std::str::from_utf8(&buffer[..size]).expect("utf8 discovery");
    assert!(payload.contains("\"type\":\"pairing_discovery\""));
    assert!(!payload.contains("\"code\""));
}
