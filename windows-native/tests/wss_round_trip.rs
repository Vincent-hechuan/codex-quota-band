use codex_quota_windows_core::network::{SyncPayload, WssService};
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, PairingManager, QuotaLink, QuotaSnapshot,
    QuotaSourceStatus, QuotaWindow, QuotaWindowStatus, ResetInventorySnapshot,
    ResetInventoryStatus, TaskSyncSnapshot, TlsIdentityKey, UpstreamFreshness,
};
use futures_util::{SinkExt, StreamExt};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use std::fmt::Debug;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::client_async;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::protocol::Message;

#[tokio::test]
async fn tls_pairing_and_authenticated_sync_complete_one_real_websocket_round_trip() {
    let identity = TlsIdentityKey::generate().expect("identity");
    let fingerprint = identity.public_key_fingerprint().expect("fingerprint");
    let service = WssService::new(
        &identity,
        PairingManager::new(fingerprint),
        sample_payload(),
    )
    .expect("service");
    let mut refresh_requests = service.subscribe_refresh_requests();
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("listener");
    let address = listener.local_addr().expect("local address");
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let server = tokio::spawn(service.clone().serve(listener, shutdown_rx));

    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let offer = service.begin_pairing(now_ms).await.expect("pairing offer");

    let pair_url = format!("wss://{address}/pair");
    let mut pair_socket = connect(&pair_url, None).await;
    pair_socket
        .send(Message::Text(
            format!(
                "{{\"type\":\"pair_request\",\"protocolVersion\":1,\"code\":\"{}\",\"clientInstanceId\":\"client_0123456789\"}}",
                offer.code
            )
            .into(),
        ))
        .await
        .expect("send pairing request");
    let pair_response = pair_socket
        .next()
        .await
        .expect("pairing response")
        .expect("valid pairing response")
        .into_text()
        .expect("text pairing response");
    let pair_json: serde_json::Value = serde_json::from_str(&pair_response).expect("pairing json");
    let token = pair_json["phoneToken"].as_str().expect("phone token");
    assert_eq!(token.len(), 64);

    let sync_url = format!("wss://{address}/sync");
    let mut sync_socket = connect(&sync_url, Some(token)).await;
    sync_socket
        .send(Message::Text(
            r#"{"type":"client_hello","transportVersion":1,"clientInstanceId":"client_0123456789","supportedQuotaVersions":[1],"supportedTaskVersions":[1]}"#
                .into(),
        ))
        .await
        .expect("send client hello");

    let server_hello = next_json(&mut sync_socket).await;
    assert_eq!(server_hello["type"], "server_hello");
    assert_eq!(server_hello["quotaVersion"], 1);
    assert_eq!(server_hello["taskVersion"], 1);

    let snapshot = next_json(&mut sync_socket).await;
    assert_eq!(snapshot["type"], "snapshot");
    assert_eq!(snapshot["quota"]["windows"][0]["remainingPercent"], 67);
    assert_eq!(snapshot["tasks"]["chatGptState"], "running");
    let wire = snapshot.to_string();
    for forbidden in ["prompt", "response", "command", "path", "cookie"] {
        assert!(!wire.contains(forbidden));
    }

    sync_socket
        .send(Message::Text(
            format!(
                "{{\"type\":\"refresh_request\",\"transportVersion\":1,\"connectionId\":\"{}\",\"scope\":\"quota\"}}",
                server_hello["connectionId"].as_str().expect("connection id")
            )
            .into(),
        ))
        .await
        .expect("send refresh request");
    tokio::time::timeout(
        std::time::Duration::from_secs(1),
        refresh_requests.changed(),
    )
    .await
    .expect("refresh request reaches the quota monitor")
    .expect("refresh request channel remains open");

    service.revoke_phone().await;
    let revoked = tokio::time::timeout(std::time::Duration::from_secs(1), sync_socket.next())
        .await
        .expect("revoked phone is disconnected promptly")
        .expect("revocation close frame")
        .expect("valid revocation close frame");
    assert!(matches!(revoked, Message::Close(_)));
    assert_eq!(service.active_sync_connections(), 0);

    shutdown_tx.send_replace(true);
    server.await.expect("server join").expect("server shutdown");
}

async fn connect(
    url: &str,
    token: Option<&str>,
) -> tokio_tungstenite::WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>> {
    let tcp = TcpStream::connect(url.trim_start_matches("wss://").split('/').next().unwrap())
        .await
        .expect("tcp connect");
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("TLS 1.3 config")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(TestCertificateVerifier))
        .with_no_client_auth();
    let connector = TlsConnector::from(Arc::new(config));
    let server_name = ServerName::try_from("codex-quota.local")
        .expect("server name")
        .to_owned();
    let tls = connector
        .connect(server_name, tcp)
        .await
        .expect("TLS connect");
    let mut request = url.into_client_request().expect("websocket request");
    if let Some(token) = token {
        request.headers_mut().insert(
            "authorization",
            format!("Bearer {token}")
                .parse()
                .expect("authorization header"),
        );
    }
    client_async(request, tls)
        .await
        .expect("websocket handshake")
        .0
}

async fn next_json<S>(socket: &mut tokio_tungstenite::WebSocketStream<S>) -> serde_json::Value
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let message = socket
        .next()
        .await
        .expect("websocket message")
        .expect("valid websocket message")
        .into_text()
        .expect("text websocket message");
    serde_json::from_str(&message).expect("JSON websocket message")
}

#[derive(Debug)]
struct TestCertificateVerifier;

impl ServerCertVerifier for TestCertificateVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}

fn sample_payload() -> SyncPayload {
    let generated_at_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    SyncPayload {
        quota: QuotaSnapshot {
            protocol_version: 1,
            generated_at: "2026-07-24T08:00:00Z".to_string(),
            source_status: QuotaSourceStatus::Ok,
            limits_collected_at: Some("2026-07-24T08:00:00Z".to_string()),
            windows: vec![QuotaWindow {
                id: "weekly".to_string(),
                name: "weekly".to_string(),
                window_minutes: 10_080,
                remaining_percent: Some(67),
                resets_at: "2026-07-31T08:00:00Z".to_string(),
                status: QuotaWindowStatus::Current,
            }],
            reset_inventory: ResetInventorySnapshot {
                status: ResetInventoryStatus::Missing,
                available_count: None,
                cached_at: None,
                items: vec![],
            },
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: CodexLinkStatus::Ok,
            },
            upstream_freshness: UpstreamFreshness::default(),
        },
        tasks: TaskSyncSnapshot {
            protocol_version: 1,
            sequence: 1,
            generated_at_ms,
            chat_gpt_state: ChatGptState::Running,
            chat_gpt_focused: false,
            tasks: vec![],
        },
    }
}
