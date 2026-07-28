use crate::network::{SyncPayload, WssService, WssServiceError};
use crate::storage::{IdentityStoreError, PhoneTokenHashStore, TlsIdentityStore};
use crate::{PairingError, PairingManager, PairingQrOffer};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use std::collections::BTreeSet;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::sync::watch;
use tokio::task::JoinHandle;

#[derive(Debug)]
pub enum HostError {
    Storage(IdentityStoreError),
    Network(WssServiceError),
    Io(io::Error),
    Pairing(PairingError),
    Serialization,
    NoPrivateAddress,
    TaskStopped,
}

impl std::fmt::Display for HostError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Storage(error) => write!(formatter, "host storage failed: {error}"),
            Self::Network(error) => write!(formatter, "host network failed: {error}"),
            Self::Io(error) => write!(formatter, "host I/O failed: {error}"),
            Self::Pairing(error) => write!(formatter, "pairing failed: {error:?}"),
            Self::Serialization => formatter.write_str("pairing payload serialization failed"),
            Self::NoPrivateAddress => formatter.write_str("no private IPv4 address is available"),
            Self::TaskStopped => formatter.write_str("host background task stopped unexpectedly"),
        }
    }
}

impl std::error::Error for HostError {}

impl From<IdentityStoreError> for HostError {
    fn from(value: IdentityStoreError) -> Self {
        Self::Storage(value)
    }
}

impl From<WssServiceError> for HostError {
    fn from(value: WssServiceError) -> Self {
        Self::Network(value)
    }
}

impl From<io::Error> for HostError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<PairingError> for HostError {
    fn from(value: PairingError) -> Self {
        Self::Pairing(value)
    }
}

#[derive(Debug, Clone)]
pub struct HostPaths {
    pub identity_file: PathBuf,
    pub phone_token_hash_file: PathBuf,
}

impl HostPaths {
    pub fn in_data_directory(directory: impl AsRef<Path>) -> Self {
        let directory = directory.as_ref();
        Self {
            identity_file: directory.join("tls-identity-v1.bin"),
            phone_token_hash_file: directory.join("phone-token-hash-v1.bin"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingPresentation {
    pub offer: PairingQrOffer,
    pub deep_link: String,
}

pub struct WindowsHost {
    service: Arc<WssService>,
    token_store: Arc<PhoneTokenHashStore>,
    shutdown: watch::Sender<bool>,
    server_task: JoinHandle<Result<(), WssServiceError>>,
    persistence_task: JoinHandle<Result<(), IdentityStoreError>>,
    port: u16,
    fingerprint_hex: String,
}

#[derive(Clone)]
pub struct HostPublisher {
    service: Arc<WssService>,
}

impl HostPublisher {
    pub async fn publish(&self, payload: SyncPayload) {
        self.service.publish(payload).await;
    }
}

impl WindowsHost {
    pub async fn start(
        paths: HostPaths,
        bind_address: SocketAddr,
        initial_payload: SyncPayload,
    ) -> Result<Self, HostError> {
        let identity = TlsIdentityStore::new(paths.identity_file).load_or_create()?;
        let fingerprint = identity
            .public_key_fingerprint()
            .map_err(IdentityStoreError::Identity)?;
        let fingerprint_hex = hex::encode(fingerprint);
        let token_store = Arc::new(PhoneTokenHashStore::new(paths.phone_token_hash_file));
        let persisted_token_hash = token_store.load()?;
        let pairing = PairingManager::from_phone_token_hash(fingerprint, persisted_token_hash);
        let service = WssService::new(&identity, pairing, initial_payload)?;
        let listener = TcpListener::bind(bind_address).await?;
        let port = listener.local_addr()?.port();
        let (shutdown, shutdown_receiver) = watch::channel(false);
        let server_task = tokio::spawn(service.clone().serve(listener, shutdown_receiver));
        let persistence_task = tokio::spawn(monitor_phone_token_hash(
            service.clone(),
            token_store.clone(),
            shutdown.subscribe(),
            persisted_token_hash,
        ));
        Ok(Self {
            service,
            token_store,
            shutdown,
            server_task,
            persistence_task,
            port,
            fingerprint_hex,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn fingerprint_hex(&self) -> &str {
        &self.fingerprint_hex
    }

    pub fn active_sync_connections(&self) -> usize {
        self.service.active_sync_connections()
    }

    pub fn subscribe_refresh_requests(&self) -> watch::Receiver<u64> {
        self.service.subscribe_refresh_requests()
    }

    pub fn publisher(&self) -> HostPublisher {
        HostPublisher {
            service: self.service.clone(),
        }
    }

    pub async fn publish(&self, payload: SyncPayload) {
        self.service.publish(payload).await;
    }

    pub async fn begin_pairing(
        &self,
        now_ms: i64,
        private_addresses: impl IntoIterator<Item = Ipv4Addr>,
    ) -> Result<PairingPresentation, HostError> {
        let endpoints = private_addresses
            .into_iter()
            .filter(|address| address.is_private() || address.is_link_local())
            .collect::<BTreeSet<_>>()
            .into_iter()
            .take(8)
            .map(|address| format!("wss://{address}:{}/pair", self.port))
            .collect::<Vec<_>>();
        if endpoints.is_empty() {
            return Err(HostError::NoPrivateAddress);
        }
        let offer = self
            .service
            .begin_pairing(now_ms)
            .await?
            .to_qr_offer(endpoints)?;
        let payload = serde_json::to_vec(&offer).map_err(|_| HostError::Serialization)?;
        let deep_link = format!(
            "codexquota://pair?offer={}",
            URL_SAFE_NO_PAD.encode(payload)
        );
        Ok(PairingPresentation { offer, deep_link })
    }

    pub async fn revoke_phone(&self) -> Result<(), HostError> {
        self.service.revoke_phone().await;
        self.token_store.save(None)?;
        Ok(())
    }

    pub async fn shutdown(self) -> Result<(), HostError> {
        self.shutdown.send_replace(true);
        self.server_task
            .await
            .map_err(|_| HostError::TaskStopped)??;
        self.persistence_task
            .await
            .map_err(|_| HostError::TaskStopped)??;
        Ok(())
    }
}

pub fn private_ipv4_addresses() -> Result<Vec<Ipv4Addr>, HostError> {
    let mut addresses = BTreeSet::new();
    for interface in if_addrs::get_if_addrs()? {
        let ip = match interface.addr {
            if_addrs::IfAddr::V4(address) => address.ip,
            if_addrs::IfAddr::V6(_) => continue,
        };
        if !ip.is_loopback() && (ip.is_private() || ip.is_link_local()) {
            addresses.insert(ip);
        }
    }
    Ok(addresses.into_iter().collect())
}

async fn monitor_phone_token_hash(
    service: Arc<WssService>,
    store: Arc<PhoneTokenHashStore>,
    mut shutdown: watch::Receiver<bool>,
    mut persisted: Option<[u8; 32]>,
) -> Result<(), IdentityStoreError> {
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        tokio::select! {
            _ = tick.tick() => {
                let current = service.phone_token_hash().await;
                if current != persisted {
                    store.save(current)?;
                    persisted = current;
                }
            }
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    let current = service.phone_token_hash().await;
                    if current != persisted {
                        store.save(current)?;
                    }
                    return Ok(());
                }
            }
        }
    }
}
