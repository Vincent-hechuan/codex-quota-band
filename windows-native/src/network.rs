use crate::{
    PairingError, PairingManager, QuotaSnapshot, QuotaSnapshotV2, QuotaSnapshotV3, SyncStreamFrame,
    TaskSyncSnapshot, TlsIdentityKey,
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Mutex, RwLock, watch};
use tokio::time::{Instant, interval_at, timeout};
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tokio_tungstenite::tungstenite::protocol::Message;
use tokio_tungstenite::{WebSocketStream, accept_hdr_async};

const MAX_PAIRING_MESSAGE_BYTES: usize = 4 * 1024;
const MAX_CLIENT_HELLO_BYTES: usize = 4 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(15);
const REFRESH_REQUEST_COOLDOWN_MS: i64 = 10_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncPayload {
    pub quota: QuotaSnapshot,
    pub tasks: TaskSyncSnapshot,
}

#[derive(Debug)]
pub enum WssServiceError {
    Io(io::Error),
    Tls,
}

impl std::fmt::Display for WssServiceError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "network I/O failed: {error}"),
            Self::Tls => formatter.write_str("TLS service configuration failed"),
        }
    }
}

impl std::error::Error for WssServiceError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::Tls => None,
        }
    }
}

impl From<io::Error> for WssServiceError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

pub struct WssService {
    tls_acceptor: TlsAcceptor,
    pairing: Arc<Mutex<PairingManager>>,
    latest: Arc<RwLock<SyncPayload>>,
    snapshot_revision: watch::Sender<u64>,
    authorization_revision: watch::Sender<u64>,
    refresh_revision: watch::Sender<u64>,
    last_refresh_request_ms: AtomicI64,
    active_sync_connections: AtomicUsize,
}

impl WssService {
    pub fn new(
        identity: &TlsIdentityKey,
        pairing: PairingManager,
        initial_payload: SyncPayload,
    ) -> Result<Arc<Self>, WssServiceError> {
        let tls_config = identity
            .rustls_server_config()
            .map_err(|_| WssServiceError::Tls)?;
        let (snapshot_revision, _) = watch::channel(0_u64);
        let (authorization_revision, _) = watch::channel(0_u64);
        let (refresh_revision, _) = watch::channel(0_u64);
        Ok(Arc::new(Self {
            tls_acceptor: TlsAcceptor::from(Arc::new(tls_config)),
            pairing: Arc::new(Mutex::new(pairing)),
            latest: Arc::new(RwLock::new(initial_payload)),
            snapshot_revision,
            authorization_revision,
            refresh_revision,
            last_refresh_request_ms: AtomicI64::new(0),
            active_sync_connections: AtomicUsize::new(0),
        }))
    }

    pub async fn begin_pairing(&self, now_ms: i64) -> Result<crate::PairingOffer, PairingError> {
        self.pairing.lock().await.begin(now_ms)
    }

    pub async fn publish(&self, payload: SyncPayload) {
        *self.latest.write().await = payload;
        let next = self.snapshot_revision.borrow().wrapping_add(1);
        self.snapshot_revision.send_replace(next);
    }

    pub async fn phone_token_hash(&self) -> Option<[u8; 32]> {
        self.pairing.lock().await.phone_token_hash()
    }

    pub async fn revoke_phone(&self) {
        self.pairing.lock().await.revoke_phone();
        self.bump_authorization_revision();
    }

    pub fn active_sync_connections(&self) -> usize {
        self.active_sync_connections.load(Ordering::Acquire)
    }

    pub fn subscribe_refresh_requests(&self) -> watch::Receiver<u64> {
        self.refresh_revision.subscribe()
    }

    pub async fn serve(
        self: Arc<Self>,
        listener: TcpListener,
        mut shutdown: watch::Receiver<bool>,
    ) -> Result<(), WssServiceError> {
        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    let (stream, peer) = accepted?;
                    if !allowed_peer(peer.ip()) {
                        continue;
                    }
                    let service = self.clone();
                    tokio::spawn(async move {
                        let _ = service.handle_connection(stream, peer).await;
                    });
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        return Ok(());
                    }
                }
            }
        }
    }

    async fn handle_connection(
        self: Arc<Self>,
        stream: TcpStream,
        _peer: SocketAddr,
    ) -> Result<(), ()> {
        let tls = timeout(HANDSHAKE_TIMEOUT, self.tls_acceptor.accept(stream))
            .await
            .map_err(|_| ())?
            .map_err(|_| ())?;
        let selected_route = Arc::new(StdMutex::new(None));
        let route_slot = selected_route.clone();
        let websocket = timeout(
            HANDSHAKE_TIMEOUT,
            accept_hdr_async(tls, move |request: &Request, mut response: Response| {
                let route = match request.uri().path() {
                    "/pair" => Route::Pair,
                    "/sync" => {
                        let token = bearer_token(request).ok_or_else(|| {
                            rejection(StatusCode::UNAUTHORIZED, "authentication required")
                        })?;
                        Route::Sync { token }
                    }
                    _ => return Err(rejection(StatusCode::NOT_FOUND, "not found")),
                };
                *route_slot.lock().expect("route mutex poisoned") = Some(route);
                response.headers_mut().insert(
                    "cache-control",
                    "no-store".parse().expect("static cache header"),
                );
                Ok(response)
            }),
        )
        .await
        .map_err(|_| ())?
        .map_err(|_| ())?;
        let route = selected_route.lock().map_err(|_| ())?.take().ok_or(())?;
        match route {
            Route::Pair => self.handle_pairing(websocket).await,
            Route::Sync { token } => self.handle_sync(websocket, token).await,
        }
    }

    async fn handle_pairing<S>(&self, mut websocket: WebSocketStream<S>) -> Result<(), ()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let message = timeout(HANDSHAKE_TIMEOUT, websocket.next())
            .await
            .map_err(|_| ())?
            .ok_or(())?
            .map_err(|_| ())?;
        let text = message.into_text().map_err(|_| ())?;
        if text.len() > MAX_PAIRING_MESSAGE_BYTES {
            return Err(());
        }
        let request: PairRequest = serde_json::from_str(&text).map_err(|_| ())?;
        request.validate()?;
        let token = self
            .pairing
            .lock()
            .await
            .complete(&request.code, now_ms())
            .map_err(|_| ())?;
        self.bump_authorization_revision();
        let response = PairSuccess {
            message_type: PairSuccessType::PairSuccess,
            protocol_version: 1,
            phone_token: token.expose(),
        };
        let response = serde_json::to_string(&response).map_err(|_| ())?;
        websocket
            .send(Message::Text(response.into()))
            .await
            .map_err(|_| ())?;
        websocket.close(None).await.map_err(|_| ())
    }

    async fn handle_sync<S>(&self, websocket: WebSocketStream<S>, token: String) -> Result<(), ()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let mut authorization_revision = self.authorization_revision.subscribe();
        if !self.pairing.lock().await.authenticate(&token) {
            return Err(());
        }
        let (mut sink, mut stream) = websocket.split();
        let first = timeout(HANDSHAKE_TIMEOUT, stream.next())
            .await
            .map_err(|_| ())?
            .ok_or(())?
            .map_err(|_| ())?;
        let first = first.into_text().map_err(|_| ())?;
        if first.len() > MAX_CLIENT_HELLO_BYTES {
            return Err(());
        }
        let hello: ClientHello = serde_json::from_str(&first).map_err(|_| ())?;
        hello.validate()?;
        let _active_connection = ActiveSyncConnection::new(&self.active_sync_connections);

        let quota_version = hello.negotiate_quota_version()?;
        let connection_id = random_connection_id()?;
        let server_hello = SyncStreamFrame::ServerHello {
            transport_version: 1,
            connection_id: connection_id.clone(),
            quota_version,
            task_version: 1,
            heartbeat_interval_ms: HEARTBEAT_INTERVAL.as_millis() as u32,
        };
        send_json(&mut sink, &server_hello).await?;

        let mut sequence = 0_u64;
        let payload = self.latest.read().await.clone();
        send_snapshot(&mut sink, &connection_id, sequence, quota_version, payload).await?;
        let mut revision = self.snapshot_revision.subscribe();
        let mut heartbeats = interval_at(Instant::now() + HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);

        loop {
            tokio::select! {
                changed = authorization_revision.changed() => {
                    if changed.is_err() {
                        return Ok(());
                    }
                    let _ = sink.send(Message::Close(None)).await;
                    return Ok(());
                }
                incoming = stream.next() => {
                    match incoming {
                        Some(Ok(Message::Ping(payload))) => sink.send(Message::Pong(payload)).await.map_err(|_| ())?,
                        Some(Ok(Message::Text(payload))) => {
                            if payload.len() > MAX_CLIENT_HELLO_BYTES {
                                return Err(());
                            }
                            let request: RefreshRequest =
                                serde_json::from_str(&payload).map_err(|_| ())?;
                            request.validate(&connection_id)?;
                            self.request_upstream_refresh();
                        }
                        Some(Ok(Message::Close(_))) | None => return Ok(()),
                        Some(Ok(_)) => {}
                        Some(Err(_)) => return Err(()),
                    }
                }
                changed = revision.changed() => {
                    if changed.is_err() {
                        return Ok(());
                    }
                    sequence = sequence.checked_add(1).ok_or(())?;
                    let payload = self.latest.read().await.clone();
                    send_snapshot(&mut sink, &connection_id, sequence, quota_version, payload).await?;
                }
                _ = heartbeats.tick() => {
                    sequence = sequence.checked_add(1).ok_or(())?;
                    let heartbeat = SyncStreamFrame::Heartbeat {
                        transport_version: 1,
                        connection_id: connection_id.clone(),
                        sequence,
                        generated_at_ms: now_ms(),
                    };
                    send_json(&mut sink, &heartbeat).await?;
                }
            }
        }
    }

    fn bump_authorization_revision(&self) {
        let next = self.authorization_revision.borrow().wrapping_add(1);
        self.authorization_revision.send_replace(next);
    }

    fn request_upstream_refresh(&self) {
        let now = now_ms();
        let mut previous = self.last_refresh_request_ms.load(Ordering::Acquire);
        loop {
            if now.saturating_sub(previous) < REFRESH_REQUEST_COOLDOWN_MS {
                let next = self.snapshot_revision.borrow().wrapping_add(1);
                self.snapshot_revision.send_replace(next);
                return;
            }
            match self.last_refresh_request_ms.compare_exchange(
                previous,
                now,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => {
                    let next = self.refresh_revision.borrow().wrapping_add(1);
                    self.refresh_revision.send_replace(next);
                    return;
                }
                Err(actual) => previous = actual,
            }
        }
    }
}

struct ActiveSyncConnection<'a> {
    count: &'a AtomicUsize,
}

impl<'a> ActiveSyncConnection<'a> {
    fn new(count: &'a AtomicUsize) -> Self {
        count.fetch_add(1, Ordering::AcqRel);
        Self { count }
    }
}

impl Drop for ActiveSyncConnection<'_> {
    fn drop(&mut self) {
        self.count.fetch_sub(1, Ordering::AcqRel);
    }
}

#[derive(Debug)]
enum Route {
    Pair,
    Sync { token: String },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PairRequest {
    #[serde(rename = "type")]
    message_type: PairRequestType,
    protocol_version: u8,
    code: String,
    client_instance_id: String,
}

impl PairRequest {
    fn validate(&self) -> Result<(), ()> {
        if self.message_type != PairRequestType::PairRequest
            || self.protocol_version != 1
            || self.code.len() != 6
            || !self.code.bytes().all(|byte| byte.is_ascii_digit())
            || !valid_connection_id(&self.client_instance_id)
        {
            return Err(());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum PairRequestType {
    PairRequest,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairSuccess<'a> {
    #[serde(rename = "type")]
    message_type: PairSuccessType,
    protocol_version: u8,
    phone_token: &'a str,
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
enum PairSuccessType {
    PairSuccess,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ClientHello {
    #[serde(rename = "type")]
    message_type: ClientHelloType,
    transport_version: u8,
    client_instance_id: String,
    supported_quota_versions: Vec<u8>,
    supported_task_versions: Vec<u8>,
}

impl ClientHello {
    fn validate(&self) -> Result<(), ()> {
        if self.message_type != ClientHelloType::ClientHello
            || self.transport_version != 1
            || !valid_connection_id(&self.client_instance_id)
            || self.supported_quota_versions.is_empty()
            || self.supported_quota_versions.len() > 8
            || self
                .supported_quota_versions
                .iter()
                .any(|version| !matches!(version, 1 | 2 | 3))
            || self
                .supported_quota_versions
                .windows(2)
                .any(|versions| versions[0] >= versions[1])
            || self.supported_task_versions != [1]
        {
            return Err(());
        }
        Ok(())
    }

    fn negotiate_quota_version(&self) -> Result<u8, ()> {
        if self.supported_quota_versions.contains(&3) {
            Ok(3)
        } else if self.supported_quota_versions.contains(&2) {
            Ok(2)
        } else if self.supported_quota_versions.contains(&1) {
            Ok(1)
        } else {
            Err(())
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum ClientHelloType {
    ClientHello,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RefreshRequest {
    #[serde(rename = "type")]
    message_type: RefreshRequestType,
    transport_version: u8,
    connection_id: String,
    scope: RefreshScope,
}

impl RefreshRequest {
    fn validate(&self, negotiated_connection_id: &str) -> Result<(), ()> {
        if self.message_type != RefreshRequestType::RefreshRequest
            || self.transport_version != 1
            || self.connection_id != negotiated_connection_id
            || self.scope != RefreshScope::Quota
        {
            return Err(());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum RefreshRequestType {
    RefreshRequest,
}

#[derive(Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum RefreshScope {
    Quota,
}

async fn send_snapshot<S>(
    sink: &mut S,
    connection_id: &str,
    sequence: u64,
    quota_version: u8,
    payload: SyncPayload,
) -> Result<(), ()>
where
    S: futures_util::Sink<Message> + Unpin,
{
    let generated_at_ms = now_ms();
    match quota_version {
        1 => {
            let frame = SyncStreamFrame::Snapshot {
                transport_version: 1,
                connection_id: connection_id.to_string(),
                sequence,
                generated_at_ms,
                quota: payload.quota,
                tasks: payload.tasks,
            };
            send_json(sink, &frame).await
        }
        2 => {
            let frame = SyncStreamFrameV2::Snapshot {
                transport_version: 1,
                connection_id: connection_id.to_string(),
                sequence,
                generated_at_ms,
                quota: QuotaSnapshotV2::from(&payload.quota),
                tasks: payload.tasks,
            };
            send_json(sink, &frame).await
        }
        3 => {
            let frame = SyncStreamFrameV3::Snapshot {
                transport_version: 1,
                connection_id: connection_id.to_string(),
                sequence,
                generated_at_ms,
                quota: QuotaSnapshotV3::from(&payload.quota),
                tasks: payload.tasks,
            };
            send_json(sink, &frame).await
        }
        _ => Err(()),
    }
}

#[derive(Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
enum SyncStreamFrameV2 {
    Snapshot {
        transport_version: u8,
        connection_id: String,
        sequence: u64,
        generated_at_ms: i64,
        quota: QuotaSnapshotV2,
        tasks: TaskSyncSnapshot,
    },
}

#[derive(Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
enum SyncStreamFrameV3 {
    Snapshot {
        transport_version: u8,
        connection_id: String,
        sequence: u64,
        generated_at_ms: i64,
        quota: QuotaSnapshotV3,
        tasks: TaskSyncSnapshot,
    },
}

async fn send_json<S, T>(sink: &mut S, value: &T) -> Result<(), ()>
where
    S: futures_util::Sink<Message> + Unpin,
    T: Serialize,
{
    let payload = serde_json::to_string(value).map_err(|_| ())?;
    sink.send(Message::Text(payload.into()))
        .await
        .map_err(|_| ())
}

fn bearer_token(request: &Request) -> Option<String> {
    let value = request.headers().get("authorization")?.to_str().ok()?;
    let token = value.strip_prefix("Bearer ")?;
    (token.len() == 64 && token.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .then(|| token.to_ascii_lowercase())
}

fn rejection(status: StatusCode, body: &str) -> ErrorResponse {
    Response::builder()
        .status(status)
        .body(Some(body.to_string()))
        .expect("valid static rejection response")
}

fn valid_connection_id(value: &str) -> bool {
    value.len() >= 16
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

fn random_connection_id() -> Result<String, ()> {
    let mut bytes = [0_u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| ())?;
    Ok(hex::encode(bytes))
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod quota_version_tests {
    use super::*;

    #[test]
    fn highest_advertised_quota_version_is_negotiated_without_mutating_v2() {
        let v1 = ClientHello {
            message_type: ClientHelloType::ClientHello,
            transport_version: 1,
            client_instance_id: "client_0123456789".to_string(),
            supported_quota_versions: vec![1],
            supported_task_versions: vec![1],
        };
        let v2 = ClientHello {
            message_type: ClientHelloType::ClientHello,
            transport_version: 1,
            client_instance_id: "client_0123456789".to_string(),
            supported_quota_versions: vec![1, 2],
            supported_task_versions: vec![1],
        };
        let v3 = ClientHello {
            message_type: ClientHelloType::ClientHello,
            transport_version: 1,
            client_instance_id: "client_0123456789".to_string(),
            supported_quota_versions: vec![1, 2, 3],
            supported_task_versions: vec![1],
        };
        assert_eq!(v1.negotiate_quota_version(), Ok(1));
        assert_eq!(v2.negotiate_quota_version(), Ok(2));
        assert_eq!(v3.negotiate_quota_version(), Ok(3));
        assert!(v3.validate().is_ok());
    }
}

fn allowed_peer(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(address) => {
            address.is_loopback() || address.is_private() || address.is_link_local()
        }
        IpAddr::V6(address) => {
            address.is_loopback() || address.is_unique_local() || address.is_unicast_link_local()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_public_peers_and_malformed_bearer_tokens() {
        assert!(allowed_peer("192.168.1.8".parse().unwrap()));
        assert!(allowed_peer("127.0.0.1".parse().unwrap()));
        assert!(!allowed_peer("8.8.8.8".parse().unwrap()));

        let valid = Request::builder()
            .uri("/sync")
            .header("authorization", format!("Bearer {}", "ab".repeat(32)))
            .body(())
            .unwrap();
        assert_eq!(bearer_token(&valid), Some("ab".repeat(32)));

        let invalid = Request::builder()
            .uri("/sync")
            .header("authorization", "Bearer short")
            .body(())
            .unwrap();
        assert_eq!(bearer_token(&invalid), None);
    }

    #[test]
    fn pairing_and_client_hello_reject_unknown_or_incompatible_fields() {
        let pairing = r#"{"type":"pair_request","protocolVersion":1,"code":"123456","clientInstanceId":"client_0123456789","prompt":"private"}"#;
        assert!(serde_json::from_str::<PairRequest>(pairing).is_err());

        let hello = r#"{"type":"client_hello","transportVersion":1,"clientInstanceId":"client_0123456789","supportedQuotaVersions":[4],"supportedTaskVersions":[1]}"#;
        let hello: ClientHello = serde_json::from_str(hello).unwrap();
        assert!(hello.validate().is_err());
    }
}
