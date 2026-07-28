//! Privacy-bounded core for the native Codex额度 Windows client.

pub mod foreground;
pub mod hook;
pub mod host;
pub mod network;
pub mod quota;
pub mod storage;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::net::Ipv4Addr;
use std::sync::Arc;
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, ZeroizeOnDrop};

const PAIRING_LIFETIME_MS: i64 = 5 * 60 * 1_000;
const PAIRING_MAX_ATTEMPTS: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TlsIdentityError {
    GenerationFailed,
    InvalidPrivateKey,
    CertificateFailed,
    ProtectionFailed,
    TlsConfigurationFailed,
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct TlsIdentityKey {
    private_key_der: Vec<u8>,
}

impl TlsIdentityKey {
    pub fn generate() -> Result<Self, TlsIdentityError> {
        let key_pair =
            rcgen::KeyPair::generate().map_err(|_| TlsIdentityError::GenerationFailed)?;
        Ok(Self {
            private_key_der: key_pair.serialize_der(),
        })
    }

    pub fn from_private_key_der(private_key_der: Vec<u8>) -> Result<Self, TlsIdentityError> {
        rcgen::KeyPair::try_from(private_key_der.as_slice())
            .map_err(|_| TlsIdentityError::InvalidPrivateKey)?;
        Ok(Self { private_key_der })
    }

    #[cfg(windows)]
    pub fn protect_for_current_user(&self) -> Result<Vec<u8>, TlsIdentityError> {
        windows_dpapi::protect(&self.private_key_der)
    }

    #[cfg(windows)]
    pub fn from_current_user_protected(protected: &[u8]) -> Result<Self, TlsIdentityError> {
        let private_key_der = windows_dpapi::unprotect(protected)?;
        Self::from_private_key_der(private_key_der)
    }

    pub fn private_key_der(&self) -> &[u8] {
        &self.private_key_der
    }

    pub fn public_key_fingerprint(&self) -> Result<[u8; 32], TlsIdentityError> {
        let key_pair = self.key_pair()?;
        Ok(Sha256::digest(key_pair.public_key_der()).into())
    }

    pub fn public_key_fingerprint_hex(&self) -> Result<String, TlsIdentityError> {
        self.public_key_fingerprint().map(hex::encode)
    }

    pub fn certificate_der(&self) -> Result<Vec<u8>, TlsIdentityError> {
        let key_pair = self.key_pair()?;
        let params = rcgen::CertificateParams::new(vec!["codex-quota.local".to_string()])
            .map_err(|_| TlsIdentityError::CertificateFailed)?;
        let certificate = params
            .self_signed(&key_pair)
            .map_err(|_| TlsIdentityError::CertificateFailed)?;
        Ok(certificate.der().to_vec())
    }

    pub fn rustls_server_config(&self) -> Result<rustls::ServerConfig, TlsIdentityError> {
        use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let certificate = CertificateDer::from(self.certificate_der()?);
        let private_key =
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.private_key_der.clone()));
        let mut config = rustls::ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|_| TlsIdentityError::TlsConfigurationFailed)?
            .with_no_client_auth()
            .with_single_cert(vec![certificate], private_key)
            .map_err(|_| TlsIdentityError::TlsConfigurationFailed)?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(config)
    }

    fn key_pair(&self) -> Result<rcgen::KeyPair, TlsIdentityError> {
        rcgen::KeyPair::try_from(self.private_key_der.as_slice())
            .map_err(|_| TlsIdentityError::InvalidPrivateKey)
    }
}

#[cfg(windows)]
mod windows_dpapi {
    use super::TlsIdentityError;
    use std::ptr;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };
    use zeroize::Zeroize;

    pub fn protect(plaintext: &[u8]) -> Result<Vec<u8>, TlsIdentityError> {
        crypt(plaintext, true)
    }

    pub fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, TlsIdentityError> {
        crypt(ciphertext, false)
    }

    fn crypt(input: &[u8], protect: bool) -> Result<Vec<u8>, TlsIdentityError> {
        let input_length =
            u32::try_from(input.len()).map_err(|_| TlsIdentityError::ProtectionFailed)?;
        let input_blob = CRYPT_INTEGER_BLOB {
            cbData: input_length,
            pbData: input.as_ptr().cast_mut(),
        };
        let mut output_blob = CRYPT_INTEGER_BLOB::default();
        let succeeded = unsafe {
            if protect {
                CryptProtectData(
                    &input_blob,
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output_blob,
                )
            } else {
                CryptUnprotectData(
                    &input_blob,
                    ptr::null_mut(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output_blob,
                )
            }
        };
        if succeeded == 0 || output_blob.pbData.is_null() {
            return Err(TlsIdentityError::ProtectionFailed);
        }
        let mut output = unsafe {
            std::slice::from_raw_parts(output_blob.pbData, output_blob.cbData as usize).to_vec()
        };
        unsafe {
            std::slice::from_raw_parts_mut(output_blob.pbData, output_blob.cbData as usize)
                .zeroize();
            LocalFree(output_blob.pbData.cast());
        }
        if output.is_empty() {
            output.zeroize();
            return Err(TlsIdentityError::ProtectionFailed);
        }
        Ok(output)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskState {
    Running,
    NeedsAuthorization,
    WaitingForReview,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SafeActivity {
    ExecutingCommand,
    ModifyingFiles,
    UsingBrowser,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatGptState {
    Running,
    NotRunning,
    HookUnavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncedTask {
    pub conversation_id: String,
    pub title: String,
    pub state: TaskState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activity: Option<SafeActivity>,
    pub updated_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskSyncSnapshot {
    pub protocol_version: u8,
    pub sequence: u64,
    pub generated_at_ms: i64,
    pub chat_gpt_state: ChatGptState,
    pub chat_gpt_focused: bool,
    pub tasks: Vec<SyncedTask>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum QuotaSourceStatus {
    Ok,
    Partial,
    Unavailable,
    Paused,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum QuotaWindowStatus {
    Current,
    PendingSync,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResetInventoryStatus {
    Cached,
    CachedDerived,
    Missing,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ComputerLinkStatus {
    Online,
    Offline,
    Paused,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CodexLinkStatus {
    Ok,
    Unavailable,
    Stale,
    FormatChanged,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaWindow {
    pub id: String,
    pub name: String,
    pub window_minutes: u32,
    pub remaining_percent: Option<u8>,
    pub resets_at: String,
    pub status: QuotaWindowStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResetInventoryItem {
    pub id: String,
    pub title: String,
    pub status: ResetItemStatus,
    /// Source-only timing. Snapshot v1 never serializes this field; negotiated
    /// quota v2 carries it in the reduced reset-card shape below.
    #[serde(skip_serializing, default)]
    pub granted_at: Option<String>,
    pub expires_at: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResetItemStatus {
    Available,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResetInventorySnapshot {
    pub status: ResetInventoryStatus,
    pub available_count: Option<u32>,
    pub cached_at: Option<String>,
    pub items: Vec<ResetInventoryItem>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaLink {
    pub computer: ComputerLinkStatus,
    pub codex: CodexLinkStatus,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UpstreamFreshnessStatus {
    Current,
    Cached,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpstreamDatasetFreshness {
    pub status: UpstreamFreshnessStatus,
    pub last_attempt_at: Option<String>,
    pub last_success_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpstreamFreshness {
    pub usage: UpstreamDatasetFreshness,
    pub reset_inventory: UpstreamDatasetFreshness,
}

impl Default for UpstreamFreshness {
    fn default() -> Self {
        let unavailable = UpstreamDatasetFreshness {
            status: UpstreamFreshnessStatus::Unavailable,
            last_attempt_at: None,
            last_success_at: None,
        };
        Self {
            usage: unavailable.clone(),
            reset_inventory: unavailable,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaSnapshot {
    pub protocol_version: u8,
    pub generated_at: String,
    pub source_status: QuotaSourceStatus,
    pub limits_collected_at: Option<String>,
    pub windows: Vec<QuotaWindow>,
    pub reset_inventory: ResetInventorySnapshot,
    pub link: QuotaLink,
    #[serde(skip_serializing, default)]
    pub upstream_freshness: UpstreamFreshness,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResetInventoryItemV2 {
    pub status: ResetItemStatus,
    pub granted_at: Option<String>,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResetInventorySnapshotV2 {
    pub status: ResetInventoryStatus,
    pub available_count: Option<u32>,
    pub cached_at: Option<String>,
    pub items: Vec<ResetInventoryItemV2>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaSnapshotV2 {
    pub protocol_version: u8,
    pub generated_at: String,
    pub source_status: QuotaSourceStatus,
    pub limits_collected_at: Option<String>,
    pub windows: Vec<QuotaWindow>,
    pub reset_inventory: ResetInventorySnapshotV2,
    pub link: QuotaLink,
}

impl From<&QuotaSnapshot> for QuotaSnapshotV2 {
    fn from(snapshot: &QuotaSnapshot) -> Self {
        Self {
            protocol_version: 2,
            generated_at: snapshot.generated_at.clone(),
            source_status: snapshot.source_status,
            limits_collected_at: snapshot.limits_collected_at.clone(),
            windows: snapshot.windows.clone(),
            reset_inventory: ResetInventorySnapshotV2 {
                status: snapshot.reset_inventory.status,
                available_count: snapshot.reset_inventory.available_count,
                cached_at: snapshot.reset_inventory.cached_at.clone(),
                items: snapshot
                    .reset_inventory
                    .items
                    .iter()
                    .map(|item| ResetInventoryItemV2 {
                        status: item.status,
                        granted_at: item.granted_at.clone(),
                        expires_at: item.expires_at.clone(),
                    })
                    .collect(),
            },
            link: snapshot.link,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QuotaSnapshotV3 {
    pub protocol_version: u8,
    pub generated_at: String,
    pub source_status: QuotaSourceStatus,
    pub limits_collected_at: Option<String>,
    pub windows: Vec<QuotaWindow>,
    pub reset_inventory: ResetInventorySnapshotV2,
    pub link: QuotaLink,
    pub upstream_freshness: UpstreamFreshness,
}

impl From<&QuotaSnapshot> for QuotaSnapshotV3 {
    fn from(snapshot: &QuotaSnapshot) -> Self {
        let v2 = QuotaSnapshotV2::from(snapshot);
        Self {
            protocol_version: 3,
            generated_at: v2.generated_at,
            source_status: v2.source_status,
            limits_collected_at: v2.limits_collected_at,
            windows: v2.windows,
            reset_inventory: v2.reset_inventory,
            link: v2.link,
            upstream_freshness: snapshot.upstream_freshness.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum SyncStreamFrame {
    ServerHello {
        transport_version: u8,
        connection_id: String,
        quota_version: u8,
        task_version: u8,
        heartbeat_interval_ms: u32,
    },
    Snapshot {
        transport_version: u8,
        connection_id: String,
        sequence: u64,
        generated_at_ms: i64,
        quota: QuotaSnapshot,
        tasks: TaskSyncSnapshot,
    },
    Heartbeat {
        transport_version: u8,
        connection_id: String,
        sequence: u64,
        generated_at_ms: i64,
    },
}

#[derive(Clone, PartialEq, Eq, Zeroize, ZeroizeOnDrop)]
pub struct PairingOffer {
    pub code: String,
    pub computer_fingerprint: String,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingQrOffer {
    pub protocol_version: u8,
    #[serde(rename = "type")]
    pub offer_type: PairingQrOfferType,
    pub computer_fingerprint: String,
    pub endpoints: Vec<String>,
    pub code: String,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PairingQrOfferType {
    PairingOffer,
}

impl PairingOffer {
    pub fn to_qr_offer(&self, endpoints: Vec<String>) -> Result<PairingQrOffer, PairingError> {
        if endpoints.is_empty()
            || endpoints.len() > 8
            || endpoints.iter().collect::<BTreeSet<_>>().len() != endpoints.len()
            || endpoints
                .iter()
                .any(|endpoint| !valid_pairing_endpoint(endpoint))
        {
            return Err(PairingError::InvalidEndpoint);
        }
        Ok(PairingQrOffer {
            protocol_version: 1,
            offer_type: PairingQrOfferType::PairingOffer,
            computer_fingerprint: self.computer_fingerprint.clone(),
            endpoints,
            code: self.code.clone(),
            expires_at_ms: self.expires_at_ms,
        })
    }
}

fn valid_pairing_endpoint(value: &str) -> bool {
    if value.len() > 128 || !value.starts_with("wss://") || !value.ends_with("/pair") {
        return false;
    }
    let authority = &value[6..value.len() - 5];
    if authority.contains(['/', '@', '?', '#']) {
        return false;
    }
    let Some((host, port)) = authority.rsplit_once(':') else {
        return false;
    };
    let Ok(ip) = host.parse::<Ipv4Addr>() else {
        return false;
    };
    let Ok(port) = port.parse::<u16>() else {
        return false;
    };
    port > 0 && (ip.is_private() || ip.is_link_local())
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct PairingToken(String);

impl PairingToken {
    pub fn expose(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingError {
    InvalidClock,
    RandomnessUnavailable,
    NoActiveOffer,
    Expired,
    InvalidCode,
    InvalidEndpoint,
}

struct ActivePairing {
    code_hash: [u8; 32],
    expires_at_ms: i64,
    attempts_remaining: u8,
}

pub struct PairingManager {
    computer_fingerprint: [u8; 32],
    active: Option<ActivePairing>,
    phone_token_hash: Option<[u8; 32]>,
}

impl PairingManager {
    pub const fn new(computer_fingerprint: [u8; 32]) -> Self {
        Self {
            computer_fingerprint,
            active: None,
            phone_token_hash: None,
        }
    }

    pub fn begin(&mut self, now_ms: i64) -> Result<PairingOffer, PairingError> {
        let expires_at_ms = now_ms
            .checked_add(PAIRING_LIFETIME_MS)
            .filter(|_| now_ms >= 0)
            .ok_or(PairingError::InvalidClock)?;
        let code = random_pairing_code()?;
        self.active = Some(ActivePairing {
            code_hash: secret_hash(b"pairing-code-v1", code.as_bytes()),
            expires_at_ms,
            attempts_remaining: PAIRING_MAX_ATTEMPTS,
        });
        Ok(PairingOffer {
            code,
            computer_fingerprint: hex::encode(self.computer_fingerprint),
            expires_at_ms,
        })
    }

    pub fn complete(
        &mut self,
        submitted_code: &str,
        now_ms: i64,
    ) -> Result<PairingToken, PairingError> {
        let active = self.active.as_mut().ok_or(PairingError::NoActiveOffer)?;
        if now_ms < 0 || now_ms > active.expires_at_ms {
            self.active = None;
            return Err(PairingError::Expired);
        }
        let submitted_hash = secret_hash(b"pairing-code-v1", submitted_code.as_bytes());
        if !bool::from(active.code_hash.ct_eq(&submitted_hash)) {
            active.attempts_remaining = active.attempts_remaining.saturating_sub(1);
            if active.attempts_remaining == 0 {
                self.active = None;
            }
            return Err(PairingError::InvalidCode);
        }

        let mut token_bytes = [0_u8; 32];
        getrandom::fill(&mut token_bytes).map_err(|_| PairingError::RandomnessUnavailable)?;
        let token = PairingToken(hex::encode(token_bytes));
        token_bytes.zeroize();
        self.phone_token_hash = Some(secret_hash(b"phone-token-v1", token.expose().as_bytes()));
        self.active = None;
        Ok(token)
    }

    pub fn authenticate(&self, token: &str) -> bool {
        let candidate = secret_hash(b"phone-token-v1", token.as_bytes());
        self.phone_token_hash
            .as_ref()
            .is_some_and(|stored| bool::from(stored.ct_eq(&candidate)))
    }

    pub fn from_phone_token_hash(
        computer_fingerprint: [u8; 32],
        phone_token_hash: Option<[u8; 32]>,
    ) -> Self {
        Self {
            computer_fingerprint,
            active: None,
            phone_token_hash,
        }
    }

    pub const fn phone_token_hash(&self) -> Option<[u8; 32]> {
        self.phone_token_hash
    }

    pub fn revoke_phone(&mut self) {
        self.active = None;
        self.phone_token_hash = None;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlertUrgency {
    Silent,
    Vibrate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AlertContext {
    pub chatgpt_focused: bool,
    pub android_foreground: bool,
    pub reconnect: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AlertDelivery {
    pub phone: bool,
    pub band: bool,
    pub urgency: Option<AlertUrgency>,
}

impl AlertDelivery {
    pub const fn none() -> Self {
        Self {
            phone: false,
            band: false,
            urgency: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NotificationMode {
    Never,
    WhenChatGptUnfocused,
    Always,
}

#[derive(Debug, Clone, Copy)]
pub struct NotificationPolicy {
    mode: NotificationMode,
    waiting_for_review: bool,
    needs_authorization: bool,
    phone: bool,
    band: bool,
}

impl Default for NotificationPolicy {
    fn default() -> Self {
        Self::new(
            NotificationMode::WhenChatGptUnfocused,
            true,
            true,
            true,
            true,
        )
    }
}

impl NotificationPolicy {
    pub const fn new(
        mode: NotificationMode,
        waiting_for_review: bool,
        needs_authorization: bool,
        phone: bool,
        band: bool,
    ) -> Self {
        Self {
            mode,
            waiting_for_review,
            needs_authorization,
            phone,
            band,
        }
    }

    pub fn plan(&self, state: TaskState, context: AlertContext) -> AlertDelivery {
        let event_enabled = match state {
            TaskState::Running => false,
            TaskState::NeedsAuthorization => self.needs_authorization,
            TaskState::WaitingForReview => self.waiting_for_review,
        };
        let timing_allows = match self.mode {
            NotificationMode::Never => false,
            NotificationMode::WhenChatGptUnfocused => !context.chatgpt_focused,
            NotificationMode::Always => true,
        };
        if !event_enabled
            || !timing_allows
            || context.android_foreground
            || (context.reconnect && state == TaskState::WaitingForReview)
            || (!self.phone && !self.band)
        {
            return AlertDelivery::none();
        }
        AlertDelivery {
            phone: self.phone,
            band: self.band,
            urgency: Some(match state {
                TaskState::NeedsAuthorization => AlertUrgency::Vibrate,
                TaskState::WaitingForReview => AlertUrgency::Silent,
                TaskState::Running => unreachable!(),
            }),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
#[serde(rename_all = "camelCase")]
pub struct TaskUpdate {
    pub conversation_id: String,
    pub turn_id: String,
    pub title: String,
    pub state: TaskState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activity: Option<SafeActivity>,
    pub updated_at_ms: i64,
}

#[derive(Default)]
pub struct TaskReducer {
    tasks: BTreeMap<String, TaskUpdate>,
    hidden: BTreeSet<String>,
}

impl TaskReducer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn tasks(&self) -> Vec<&TaskUpdate> {
        self.tasks.values().collect()
    }

    pub fn band_tasks(&self) -> Vec<&TaskUpdate> {
        let mut tasks: Vec<_> = self
            .tasks
            .values()
            .filter(|task| !self.hidden.contains(&task.conversation_id))
            .collect();
        tasks.sort_by(task_order);
        tasks.truncate(3);
        tasks
    }

    pub fn phone_tasks(&self) -> Vec<&TaskUpdate> {
        let mut active: Vec<_> = self
            .tasks
            .values()
            .filter(|task| {
                task.state != TaskState::WaitingForReview
                    && !self.hidden.contains(&task.conversation_id)
            })
            .collect();
        let mut waiting: Vec<_> = self
            .tasks
            .values()
            .filter(|task| {
                task.state == TaskState::WaitingForReview
                    && !self.hidden.contains(&task.conversation_id)
            })
            .collect();
        active.sort_by(task_order);
        waiting.sort_by(task_order);
        waiting.truncate(10);
        active.extend(waiting);
        active
    }

    pub fn sync_snapshot(
        &self,
        sequence: u64,
        generated_at_ms: i64,
        chat_gpt_state: ChatGptState,
        chat_gpt_focused: bool,
    ) -> TaskSyncSnapshot {
        TaskSyncSnapshot {
            protocol_version: 1,
            sequence,
            generated_at_ms,
            chat_gpt_state,
            chat_gpt_focused,
            tasks: self
                .phone_tasks()
                .into_iter()
                .map(|task| SyncedTask {
                    conversation_id: task.conversation_id.clone(),
                    title: task.title.clone(),
                    state: task.state,
                    activity: task.activity,
                    updated_at_ms: task.updated_at_ms,
                })
                .collect(),
        }
    }

    pub fn hide_task(&mut self, conversation_id: &str) -> bool {
        if self.tasks.contains_key(conversation_id) {
            self.hidden.insert(conversation_id.to_string());
            true
        } else {
            false
        }
    }

    pub fn delete_waiting(&mut self, conversation_id: &str) -> bool {
        let can_delete = self
            .tasks
            .get(conversation_id)
            .is_some_and(|task| task.state == TaskState::WaitingForReview);
        if !can_delete {
            return false;
        }
        self.tasks.remove(conversation_id);
        self.hidden.remove(conversation_id);
        true
    }

    pub fn clear_waiting(&mut self) -> usize {
        let waiting_ids: Vec<_> = self
            .tasks
            .values()
            .filter(|task| task.state == TaskState::WaitingForReview)
            .map(|task| task.conversation_id.clone())
            .collect();
        for conversation_id in &waiting_ids {
            self.tasks.remove(conversation_id);
            self.hidden.remove(conversation_id);
        }
        waiting_ids.len()
    }

    pub fn ingest_json(
        &mut self,
        payload: &str,
        observed_at_ms: i64,
    ) -> Result<Option<TaskUpdate>, String> {
        self.ingest_json_with_title(payload, observed_at_ms, None)
    }

    pub fn ingest_json_with_title(
        &mut self,
        payload: &str,
        observed_at_ms: i64,
        indexed_title: Option<&str>,
    ) -> Result<Option<TaskUpdate>, String> {
        if payload.len() > 256 * 1024 || observed_at_ms < 0 {
            return Err("invalid hook event".to_string());
        }
        let event: RawHookEvent =
            serde_json::from_str(payload).map_err(|_| "invalid hook event".to_string())?;
        if !valid_identity(&event.session_id) || !valid_identity(&event.turn_id) {
            return Err("invalid hook event".to_string());
        }
        let (state, activity) = match event.hook_event_name.as_str() {
            "UserPromptSubmit" => (TaskState::Running, None),
            "PermissionRequest" => (TaskState::NeedsAuthorization, None),
            "Stop" => (TaskState::WaitingForReview, None),
            "PreToolUse" | "PostToolUse" => (
                TaskState::Running,
                event.tool_name.as_deref().and_then(safe_activity),
            ),
            _ => return Ok(None),
        };
        let title = indexed_title
            .map(sanitize_title)
            .filter(|title| title != "任务")
            .or_else(|| {
                self.tasks
                    .get(&event.session_id)
                    .filter(|task| task.title != "任务")
                    .map(|task| task.title.clone())
            })
            .unwrap_or_else(|| "任务".to_string());
        let update = TaskUpdate {
            conversation_id: event.session_id,
            turn_id: event.turn_id,
            title,
            state,
            activity,
            updated_at_ms: observed_at_ms,
        };
        self.hidden.remove(&update.conversation_id);
        self.tasks
            .insert(update.conversation_id.clone(), update.clone());
        Ok(Some(update))
    }

    pub fn ingest_update(&mut self, mut update: TaskUpdate) -> Result<Option<TaskUpdate>, String> {
        if !valid_identity(&update.conversation_id)
            || !valid_identity(&update.turn_id)
            || update.title.is_empty()
            || update.title.chars().count() > 16
            || update.updated_at_ms < 0
        {
            return Err("invalid sanitized hook update".to_string());
        }
        if let Some(existing) = self.tasks.get(&update.conversation_id) {
            if existing.updated_at_ms > update.updated_at_ms {
                return Ok(None);
            }
            if update.title == "任务" && existing.title != "任务" {
                update.title = existing.title.clone();
            }
        }
        self.hidden.remove(&update.conversation_id);
        self.tasks
            .insert(update.conversation_id.clone(), update.clone());
        Ok(Some(update))
    }

    pub fn refresh_indexed_title(&mut self, conversation_id: &str, indexed_title: &str) -> bool {
        if !valid_identity(conversation_id) {
            return false;
        }
        let title = sanitize_title(indexed_title);
        if title == "任务" {
            return false;
        }
        let Some(task) = self.tasks.get_mut(conversation_id) else {
            return false;
        };
        if task.title != "任务" {
            return false;
        }
        task.title = title;
        true
    }
}

#[derive(Deserialize)]
struct RawHookEvent {
    session_id: String,
    turn_id: String,
    hook_event_name: String,
    tool_name: Option<String>,
}

fn sanitize_title(candidate: &str) -> String {
    let safe_text = candidate
        .split_whitespace()
        .filter(|part| !is_private_title_part(part))
        .collect::<Vec<_>>()
        .join(" ");
    let title: String = safe_text.chars().take(16).collect();
    if title.is_empty() {
        "任务".to_string()
    } else {
        title
    }
}

fn is_private_title_part(part: &str) -> bool {
    let lower = part.to_ascii_lowercase();
    lower.starts_with("http://")
        || lower.starts_with("https://")
        || lower.starts_with("token=")
        || lower.starts_with("password=")
        || lower.starts_with("secret=")
        || lower.starts_with("api_key=")
        || lower.starts_with("apikey=")
        || lower.starts_with("authorization=")
        || lower.contains("sk-")
}

fn safe_activity(tool_name: &str) -> Option<SafeActivity> {
    if tool_name == "Bash" {
        Some(SafeActivity::ExecutingCommand)
    } else if tool_name == "apply_patch" {
        Some(SafeActivity::ModifyingFiles)
    } else {
        let lower = tool_name.to_ascii_lowercase();
        (lower.contains("browser") || lower.contains("chrome"))
            .then_some(SafeActivity::UsingBrowser)
    }
}

fn valid_identity(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= 128 && !value.chars().any(char::is_control)
}

fn state_priority(state: TaskState) -> u8 {
    match state {
        TaskState::NeedsAuthorization => 0,
        TaskState::Running => 1,
        TaskState::WaitingForReview => 2,
    }
}

fn task_order(left: &&TaskUpdate, right: &&TaskUpdate) -> std::cmp::Ordering {
    state_priority(left.state)
        .cmp(&state_priority(right.state))
        .then_with(|| right.updated_at_ms.cmp(&left.updated_at_ms))
        .then_with(|| left.conversation_id.cmp(&right.conversation_id))
}

fn random_pairing_code() -> Result<String, PairingError> {
    const RANGE: u32 = 1_000_000;
    const ACCEPT_BELOW: u32 = u32::MAX - (u32::MAX % RANGE);
    loop {
        let mut bytes = [0_u8; 4];
        getrandom::fill(&mut bytes).map_err(|_| PairingError::RandomnessUnavailable)?;
        let value = u32::from_le_bytes(bytes);
        bytes.zeroize();
        if value < ACCEPT_BELOW {
            return Ok(format!("{:06}", value % RANGE));
        }
    }
}

fn secret_hash(domain: &[u8], secret: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(domain);
    hasher.update([0]);
    hasher.update(secret);
    hasher.finalize().into()
}

#[cfg(test)]
mod quota_v2_tests {
    use super::*;

    #[test]
    fn quota_v2_exposes_card_timing_without_card_identity_or_title() {
        let snapshot = QuotaSnapshot {
            protocol_version: 1,
            generated_at: "2026-07-26T10:00:00.000Z".to_string(),
            source_status: QuotaSourceStatus::Ok,
            limits_collected_at: None,
            windows: Vec::new(),
            reset_inventory: ResetInventorySnapshot {
                status: ResetInventoryStatus::Cached,
                available_count: Some(1),
                cached_at: Some("2026-07-26T10:00:00.000Z".to_string()),
                items: vec![ResetInventoryItem {
                    id: "private-card-id".to_string(),
                    title: "private-card-title".to_string(),
                    status: ResetItemStatus::Available,
                    granted_at: Some("2026-07-25T09:00:00.000Z".to_string()),
                    expires_at: "2026-07-31T19:49:39.737Z".to_string(),
                }],
            },
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: CodexLinkStatus::Ok,
            },
            upstream_freshness: UpstreamFreshness::default(),
        };

        let wire = serde_json::to_string(&QuotaSnapshotV2::from(&snapshot)).unwrap();
        assert!(wire.contains("grantedAt"));
        assert!(wire.contains("expiresAt"));
        assert!(!wire.contains("private-card-id"));
        assert!(!wire.contains("private-card-title"));
        assert!(
            !wire.contains("upstreamFreshness"),
            "adding freshness to the already-shipped v2 contract disconnects strict Android clients"
        );
        let v3 = serde_json::to_string(&QuotaSnapshotV3::from(&snapshot)).unwrap();
        assert!(v3.contains("upstreamFreshness"));
    }
}
