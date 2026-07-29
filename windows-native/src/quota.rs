use crate::{
    CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus, QuotaWindow,
    QuotaWindowStatus, ResetInventoryItem, ResetInventorySnapshot, ResetInventoryStatus,
    ResetItemStatus, UpstreamDatasetFreshness, UpstreamFreshness, UpstreamFreshnessStatus,
};
use brotli::Decompressor;
use chrono::{DateTime, SecondsFormat, Utc};
use flate2::read::{DeflateDecoder, GzDecoder, ZlibDecoder};
use reqwest::blocking::Client;
use reqwest::header::{AUTHORIZATION, HeaderValue};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::cmp::Ordering;
use std::fs::{self};
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::time::Duration;
use std::time::SystemTime;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

const BLOCK_FILE_MAGIC: u32 = 0xc104cac3;
const BLOCK_HEADER_SIZE: usize = 8_192;
const ENTRY_STORE_SIZE: usize = 256;
const ENTRY_KEY_OFFSET: usize = 96;
const INLINE_KEY_CAPACITY: usize = 160;
const USAGE_ENDPOINT: &[u8] = b"/wham/usage";
const RESET_ENDPOINT: &[u8] = b"/wham/rate-limit-reset-credits";
const DIRECT_RESET_ENDPOINT: &str = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits";
const DIRECT_RESET_CACHE_FILE: &str = "reset-credit-details-v1.json";
const DIRECT_USAGE_CACHE_FILE: &str = "usage-details-v1.json";
const DIRECT_FRESHNESS_CACHE_FILE: &str = "upstream-freshness-v1.json";
const DIRECT_RESET_TIMEOUT: Duration = Duration::from_secs(8);

#[derive(Debug)]
pub enum QuotaCollectorError {
    Io(io::Error),
}

impl std::fmt::Display for QuotaCollectorError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "quota source I/O failed: {error}"),
        }
    }
}

impl std::error::Error for QuotaCollectorError {}

impl From<io::Error> for QuotaCollectorError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CodexDataPaths {
    pub sessions_path: PathBuf,
    pub reset_cache_path: Option<PathBuf>,
    pub auth_path: PathBuf,
    pub direct_reset_cache_path: PathBuf,
    pub direct_usage_cache_path: PathBuf,
    pub direct_freshness_cache_path: PathBuf,
}

impl CodexDataPaths {
    pub fn discover() -> Result<Self, QuotaCollectorError> {
        let home = std::env::var_os("USERPROFILE")
            .map(PathBuf::from)
            .or_else(|| {
                let drive = std::env::var_os("HOMEDRIVE")?;
                let path = std::env::var_os("HOMEPATH")?;
                Some(PathBuf::from(drive).join(path))
            })
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "USERPROFILE is unavailable"))?;
        let local_app_data = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, "LOCALAPPDATA is unavailable")
            })?;
        let packages = local_app_data.join("Packages");
        let package_name = fs::read_dir(&packages)
            .ok()
            .into_iter()
            .flatten()
            .filter_map(Result::ok)
            .filter_map(|entry| entry.file_name().into_string().ok())
            .filter(|name| valid_chatgpt_package(name))
            .max();
        let reset_cache_path = package_name.map(|name| {
            packages
                .join(name)
                .join("LocalCache")
                .join("Roaming")
                .join("Codex")
                .join("web")
                .join("Codex")
                .join("Default")
                .join("Cache")
                .join("Cache_Data")
        });
        Ok(Self {
            sessions_path: home.join(".codex").join("sessions"),
            reset_cache_path,
            auth_path: home.join(".codex").join("auth.json"),
            direct_reset_cache_path: local_app_data
                .join("CodexQuota")
                .join("0.4.0")
                .join(DIRECT_RESET_CACHE_FILE),
            direct_usage_cache_path: local_app_data
                .join("CodexQuota")
                .join("0.4.0")
                .join(DIRECT_USAGE_CACHE_FILE),
            direct_freshness_cache_path: local_app_data
                .join("CodexQuota")
                .join("0.4.0")
                .join(DIRECT_FRESHNESS_CACHE_FILE),
        })
    }
}

#[derive(Debug, Clone)]
pub struct QuotaCollector {
    paths: CodexDataPaths,
}

/// Local-only direct-query result. It deliberately excludes card identifiers,
/// titles, descriptions and every credential-related value.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectResetCardTiming {
    pub granted_at: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectResetInventory {
    pub schema_version: u8,
    pub fetched_at: String,
    pub available_count: u32,
    pub cards: Vec<DirectResetCardTiming>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectUsageSnapshot {
    pub schema_version: u8,
    pub fetched_at: String,
    pub windows: Vec<QuotaWindow>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirectResetRefreshError {
    CredentialsUnavailable,
    Unauthorized,
    RequestFailed,
    ResponseFormatChanged,
    HttpStatus(u16),
}

impl std::fmt::Display for DirectResetRefreshError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::CredentialsUnavailable => formatter.write_str("Codex 凭证不可用"),
            Self::Unauthorized => formatter.write_str("Codex 凭证已失效或授权请求被拒绝"),
            Self::RequestFailed => formatter.write_str("无法请求 Codex 重置卡信息"),
            Self::ResponseFormatChanged => formatter.write_str("Codex 重置卡响应格式已变化"),
            Self::HttpStatus(status) => write!(formatter, "Codex 重置卡请求返回 HTTP {status}"),
        }
    }
}

impl std::error::Error for DirectResetRefreshError {}

/// A local-only, privacy-safe category for the most recent explicit quota
/// confirmation. It never contains an HTTP body, credential, URL parameter,
/// or server-provided error detail.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpstreamConfirmationErrorCode {
    AuthUnavailable,
    AuthRejected,
    Network,
    ResponseFormat,
    UpstreamHttp,
    LocalWrite,
}

impl UpstreamConfirmationErrorCode {
    pub fn from_refresh_error(error: DirectResetRefreshError) -> Self {
        match error {
            DirectResetRefreshError::CredentialsUnavailable => Self::AuthUnavailable,
            DirectResetRefreshError::Unauthorized => Self::AuthRejected,
            DirectResetRefreshError::RequestFailed => Self::Network,
            DirectResetRefreshError::ResponseFormatChanged => Self::ResponseFormat,
            DirectResetRefreshError::HttpStatus(_) => Self::UpstreamHttp,
        }
    }

    pub fn diagnostic_label(self) -> &'static str {
        match self {
            Self::AuthUnavailable => "AUTH_UNAVAILABLE",
            Self::AuthRejected => "AUTH_REJECTED",
            Self::Network => "NETWORK",
            Self::ResponseFormat => "RESPONSE_FORMAT",
            Self::UpstreamHttp => "UPSTREAM_HTTP",
            Self::LocalWrite => "LOCAL_WRITE",
        }
    }
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
struct CodexAuthFile {
    tokens: CodexAuthTokens,
}

#[derive(Deserialize, Zeroize, ZeroizeOnDrop)]
struct CodexAuthTokens {
    access_token: String,
}

pub fn load_cached_snapshot(path: &Path, now: DateTime<Utc>) -> Option<QuotaSnapshot> {
    let text = fs::read_to_string(path).ok()?;
    let mut snapshot = serde_json::from_str::<QuotaSnapshot>(&text).ok()?;
    if snapshot.protocol_version != 1
        || snapshot.windows.len() > 8
        || snapshot.reset_inventory.items.len() > 64
    {
        return None;
    }
    snapshot.generated_at = format_timestamp(now);
    snapshot.source_status = QuotaSourceStatus::Partial;
    snapshot.link.computer = ComputerLinkStatus::Online;
    snapshot.link.codex = CodexLinkStatus::Stale;
    let had_cached_inventory = matches!(
        snapshot.reset_inventory.status,
        ResetInventoryStatus::Cached | ResetInventoryStatus::CachedDerived
    );
    if had_cached_inventory {
        snapshot.reset_inventory.items.retain(|item| {
            parse_value_timestamp(&Value::String(item.expires_at.clone()))
                .is_some_and(|expires| expires > now)
        });
        snapshot.reset_inventory.available_count =
            Some(snapshot.reset_inventory.items.len() as u32);
        snapshot.reset_inventory.status = ResetInventoryStatus::CachedDerived;
    }
    Some(snapshot)
}

pub fn save_cached_snapshot(
    path: &Path,
    snapshot: &QuotaSnapshot,
) -> Result<(), QuotaCollectorError> {
    if snapshot.protocol_version != 1 || !matches!(snapshot.link.codex, CodexLinkStatus::Ok) {
        return Ok(());
    }
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    let payload = serde_json::to_vec(snapshot)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "snapshot serialization failed"))?;
    fs::write(&temporary, payload)?;
    match fs::rename(&temporary, path) {
        Ok(()) => Ok(()),
        Err(error) => {
            let _ = fs::remove_file(&temporary);
            Err(error.into())
        }
    }
}

impl QuotaCollector {
    pub fn new(paths: CodexDataPaths) -> Self {
        Self { paths }
    }

    pub fn discover() -> Result<Self, QuotaCollectorError> {
        Ok(Self::new(CodexDataPaths::discover()?))
    }

    pub fn paths(&self) -> &CodexDataPaths {
        &self.paths
    }

    /// Fetches the reset-card timing with the local Codex credential and stores
    /// only its allowlisted, local-only summary. Nothing from auth.json is
    /// persisted, logged or returned from this API.
    pub fn refresh_direct_reset_inventory(
        &self,
        now: DateTime<Utc>,
    ) -> Result<DirectResetInventory, DirectResetRefreshError> {
        let mut access_token = read_access_token(&self.paths.auth_path)?;
        let result = fetch_direct_reset_inventory(access_token.as_str(), now);
        access_token.zeroize();
        let inventory = result?;
        save_direct_reset_inventory(&self.paths.direct_reset_cache_path, &inventory)?;
        Ok(inventory)
    }

    /// Refreshes both upstream quota sources. Failures are persisted as a
    /// freshness transition while the last sanitized values remain available
    /// as cache; no request error or credential data leaves Windows.
    pub fn refresh_upstream_freshness(&self, now: DateTime<Utc>) -> UpstreamFreshness {
        self.refresh_upstream_freshness_with_diagnostic(now).0
    }

    /// Refreshes upstream quota sources and returns a local-only diagnostic
    /// category for the usage query when an explicit confirmation fails.
    pub fn refresh_upstream_freshness_with_diagnostic(
        &self,
        now: DateTime<Utc>,
    ) -> (UpstreamFreshness, Option<UpstreamConfirmationErrorCode>) {
        let prior =
            load_upstream_freshness(&self.paths.direct_freshness_cache_path).unwrap_or_default();
        let token = read_access_token(&self.paths.auth_path);
        let (usage_ok, reset_ok, usage_diagnostic) = if let Ok(mut token) = token {
            let usage = fetch_direct_usage(token.as_str(), now);
            let reset = fetch_direct_reset_inventory(token.as_str(), now);
            token.zeroize();
            let usage_ok = usage.as_ref().is_ok_and(|snapshot| {
                save_direct_usage(&self.paths.direct_usage_cache_path, snapshot).is_ok()
            });
            let reset_ok = reset.as_ref().is_ok_and(|snapshot| {
                save_direct_reset_inventory(&self.paths.direct_reset_cache_path, snapshot).is_ok()
            });
            let usage_diagnostic = match usage {
                Ok(_) if usage_ok => None,
                Ok(_) => Some(UpstreamConfirmationErrorCode::LocalWrite),
                Err(error) => Some(UpstreamConfirmationErrorCode::from_refresh_error(error)),
            };
            (usage_ok, reset_ok, usage_diagnostic)
        } else {
            let error = token.unwrap_err();
            (
                false,
                false,
                Some(UpstreamConfirmationErrorCode::from_refresh_error(error)),
            )
        };
        let freshness = UpstreamFreshness {
            usage: next_freshness(
                prior.usage,
                usage_ok,
                self.paths.direct_usage_cache_path.exists(),
                now,
            ),
            reset_inventory: next_freshness(
                prior.reset_inventory,
                reset_ok,
                self.paths.direct_reset_cache_path.exists(),
                now,
            ),
        };
        let _ = save_upstream_freshness(&self.paths.direct_freshness_cache_path, &freshness);
        (freshness, usage_diagnostic)
    }

    pub fn collect(&self, now: DateTime<Utc>) -> Result<QuotaSnapshot, QuotaCollectorError> {
        let upstream_freshness =
            load_upstream_freshness(&self.paths.direct_freshness_cache_path).unwrap_or_default();
        let direct_usage = self.read_direct_usage(now)?;
        let usage = if direct_usage.is_none() {
            self.read_usage()?
        } else {
            None
        };
        let session = if usage.is_none() {
            read_latest_rate_limits(&self.paths.sessions_path)?
        } else {
            None
        };
        let reset_inventory = self.read_reset_inventory(now)?;
        let (windows, limits_collected_at, codex_link) = if let Some(direct) = direct_usage {
            let link = if matches!(
                upstream_freshness.usage.status,
                UpstreamFreshnessStatus::Current
            ) {
                CodexLinkStatus::Ok
            } else {
                CodexLinkStatus::Stale
            };
            (direct.windows, Some(direct.fetched_at), link)
        } else if let Some(response) = usage {
            let rate_limit = response.body.get("rate_limit");
            let windows = rate_limit
                .and_then(|value| value.as_object())
                .map(|rate_limit| {
                    [
                        ("primary", rate_limit.get("primary_window")),
                        ("secondary", rate_limit.get("secondary_window")),
                    ]
                    .into_iter()
                    .filter_map(|(slot, value)| {
                        value.and_then(|value| normalize_window(slot, value, now))
                    })
                    .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            let codex_link = if rate_limit.is_some_and(Value::is_object) {
                CodexLinkStatus::Ok
            } else {
                CodexLinkStatus::FormatChanged
            };
            (
                windows,
                Some(format_timestamp(response.cached_at)),
                codex_link,
            )
        } else if let Some(response) = session {
            let windows = response
                .rate_limits
                .into_iter()
                .flat_map(|(slot, value)| normalize_legacy_window(&slot, &value, now))
                .collect::<Vec<_>>();
            (
                windows,
                Some(format_timestamp(response.collected_at)),
                CodexLinkStatus::Ok,
            )
        } else {
            (Vec::new(), None, CodexLinkStatus::Unavailable)
        };
        let has_source = !matches!(codex_link, CodexLinkStatus::Unavailable);
        let source_status = if matches!(
            upstream_freshness.usage.status,
            UpstreamFreshnessStatus::Current
        ) && matches!(
            upstream_freshness.reset_inventory.status,
            UpstreamFreshnessStatus::Current
        ) {
            QuotaSourceStatus::Ok
        } else if has_source
            && matches!(
                reset_inventory.status,
                ResetInventoryStatus::Cached | ResetInventoryStatus::CachedDerived
            )
        {
            QuotaSourceStatus::Ok
        } else if has_source {
            QuotaSourceStatus::Partial
        } else {
            QuotaSourceStatus::Unavailable
        };
        Ok(QuotaSnapshot {
            protocol_version: 1,
            generated_at: format_timestamp(now),
            source_status,
            limits_collected_at,
            windows,
            reset_inventory,
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: codex_link,
            },
            upstream_freshness,
        })
    }

    fn read_usage(&self) -> Result<Option<CachedJson>, QuotaCollectorError> {
        let Some(path) = self.paths.reset_cache_path.as_ref() else {
            return Ok(None);
        };
        find_cached_json(path, USAGE_ENDPOINT)
    }

    fn read_direct_usage(
        &self,
        _now: DateTime<Utc>,
    ) -> Result<Option<DirectUsageSnapshot>, QuotaCollectorError> {
        let text = match fs::read_to_string(&self.paths.direct_usage_cache_path) {
            Ok(text) => text,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        Ok(serde_json::from_str::<DirectUsageSnapshot>(&text)
            .ok()
            .filter(|snapshot| snapshot.schema_version == 1))
    }

    fn read_reset_inventory(
        &self,
        now: DateTime<Utc>,
    ) -> Result<ResetInventorySnapshot, QuotaCollectorError> {
        if let Some(inventory) = self.read_direct_reset_inventory(now)? {
            return Ok(inventory);
        }
        let Some(path) = self.paths.reset_cache_path.as_ref() else {
            return Ok(ResetInventorySnapshot {
                status: ResetInventoryStatus::Missing,
                available_count: None,
                cached_at: None,
                items: Vec::new(),
            });
        };
        let Some(response) = find_cached_json(path, RESET_ENDPOINT)? else {
            return Ok(ResetInventorySnapshot {
                status: ResetInventoryStatus::Unavailable,
                available_count: None,
                cached_at: None,
                items: Vec::new(),
            });
        };
        let Some(available_count) = response.body.get("available_count").and_then(Value::as_u64)
        else {
            return Ok(ResetInventorySnapshot {
                status: ResetInventoryStatus::Unavailable,
                available_count: None,
                cached_at: None,
                items: Vec::new(),
            });
        };
        let Some(credits) = response.body.get("credits").and_then(Value::as_array) else {
            return Ok(ResetInventorySnapshot {
                status: ResetInventoryStatus::Unavailable,
                available_count: None,
                cached_at: None,
                items: Vec::new(),
            });
        };
        let mut available = credits
            .iter()
            .filter(|credit| credit.get("status").and_then(Value::as_str) == Some("available"))
            .filter_map(sanitize_credit)
            .filter(|item| item.expires_at > format_timestamp(now))
            .collect::<Vec<_>>();
        available.sort_by(|left, right| left.expires_at.cmp(&right.expires_at));
        let derived = available.len() != available_count as usize;
        Ok(ResetInventorySnapshot {
            status: if derived {
                ResetInventoryStatus::CachedDerived
            } else {
                ResetInventoryStatus::Cached
            },
            available_count: Some(available.len() as u32),
            cached_at: Some(format_timestamp(response.cached_at)),
            items: available,
        })
    }

    fn read_direct_reset_inventory(
        &self,
        now: DateTime<Utc>,
    ) -> Result<Option<ResetInventorySnapshot>, QuotaCollectorError> {
        let text = match fs::read_to_string(&self.paths.direct_reset_cache_path) {
            Ok(text) => text,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        let inventory = match serde_json::from_str::<DirectResetInventory>(&text) {
            Ok(inventory) if inventory.schema_version == 1 => inventory,
            _ => return Ok(None),
        };
        let mut cards = inventory
            .cards
            .into_iter()
            .filter(|card| {
                parse_value_timestamp(&Value::String(card.expires_at.clone()))
                    .is_some_and(|expires_at| expires_at > now)
            })
            .collect::<Vec<_>>();
        cards.sort_by(|left, right| left.expires_at.cmp(&right.expires_at));
        let derived = cards.len() != inventory.available_count as usize;
        Ok(Some(ResetInventorySnapshot {
            status: if derived {
                ResetInventoryStatus::CachedDerived
            } else {
                ResetInventoryStatus::Cached
            },
            available_count: Some(cards.len() as u32),
            cached_at: Some(inventory.fetched_at),
            items: cards
                .into_iter()
                .enumerate()
                .map(|(index, card)| ResetInventoryItem {
                    id: format!("reset-credit-{}", index + 1),
                    title: "Full reset".to_string(),
                    status: ResetItemStatus::Available,
                    granted_at: Some(card.granted_at),
                    expires_at: card.expires_at,
                })
                .collect(),
        }))
    }
}

#[derive(Debug, Clone)]
struct CachedJson {
    cached_at: DateTime<Utc>,
    body: Value,
}

#[derive(Debug)]
struct LegacyRateLimits {
    collected_at: DateTime<Utc>,
    rate_limits: Vec<(String, Value)>,
}

fn valid_chatgpt_package(name: &str) -> bool {
    ["OpenAI.Codex_", "OpenAI.ChatGPT_"]
        .into_iter()
        .any(|prefix| {
            name.strip_prefix(prefix).is_some_and(|suffix| {
                !suffix.is_empty() && suffix.chars().all(|c| c.is_ascii_alphanumeric())
            })
        })
}

fn read_latest_rate_limits(root: &Path) -> Result<Option<LegacyRateLimits>, QuotaCollectorError> {
    let mut files = Vec::new();
    visit_jsonl_files(root, &mut files)?;
    files.sort_by(|left, right| right.1.partial_cmp(&left.1).unwrap_or(Ordering::Equal));
    let mut latest = None;
    for (path, modified) in files {
        let text = fs::read_to_string(path)?;
        for line in text.lines() {
            if !line.contains("\"rate_limits\"") {
                continue;
            }
            let Ok(event) = serde_json::from_str::<Value>(line) else {
                continue;
            };
            let Some(rate_limits) = find_named_object(&event, "rate_limits") else {
                continue;
            };
            let collected_at = event
                .get("timestamp")
                .and_then(parse_value_timestamp)
                .unwrap_or(modified);
            let mut values = Vec::new();
            for (slot, key) in [("primary", "primary"), ("secondary", "secondary")] {
                if let Some(value) = rate_limits.get(key) {
                    if value.is_object() {
                        values.push((slot.to_string(), value.clone()));
                    }
                }
            }
            if latest
                .as_ref()
                .is_none_or(|current: &LegacyRateLimits| collected_at > current.collected_at)
            {
                latest = Some(LegacyRateLimits {
                    collected_at,
                    rate_limits: values,
                });
            }
        }
    }
    Ok(latest)
}

fn visit_jsonl_files(
    root: &Path,
    files: &mut Vec<(PathBuf, DateTime<Utc>)>,
) -> Result<(), QuotaCollectorError> {
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        if entry.file_type()?.is_dir() {
            visit_jsonl_files(&path, files)?;
        } else if path.extension().and_then(|value| value.to_str()) == Some("jsonl") {
            let modified = entry
                .metadata()
                .and_then(|metadata| metadata.modified())
                .map(format_system_time)
                .unwrap_or_else(|_| DateTime::<Utc>::UNIX_EPOCH);
            files.push((path, modified));
        }
    }
    Ok(())
}

fn find_named_object<'a>(value: &'a Value, target: &str) -> Option<&'a Value> {
    if let Some(object) = value.as_object() {
        if let Some(found) = object.get(target).filter(|value| value.is_object()) {
            return Some(found);
        }
        for child in object.values() {
            if let Some(found) = find_named_object(child, target) {
                return Some(found);
            }
        }
    } else if let Some(array) = value.as_array() {
        for child in array {
            if let Some(found) = find_named_object(child, target) {
                return Some(found);
            }
        }
    }
    None
}

fn normalize_legacy_window(slot: &str, value: &Value, now: DateTime<Utc>) -> Option<QuotaWindow> {
    let window_minutes = value.get("window_minutes").and_then(Value::as_u64)? as u32;
    let used_percent = value.get("used_percent").and_then(Value::as_f64)?;
    let resets_at = value.get("resets_at").and_then(Value::as_i64)?;
    normalize_window_values(slot, window_minutes, used_percent, resets_at, now)
}

fn normalize_window(slot: &str, value: &Value, now: DateTime<Utc>) -> Option<QuotaWindow> {
    let seconds = value.get("limit_window_seconds").and_then(Value::as_u64)?;
    if seconds == 0 || seconds % 60 != 0 {
        return None;
    }
    let window_minutes = u32::try_from(seconds / 60).ok()?;
    let used_percent = value.get("used_percent").and_then(Value::as_f64)?;
    let resets_at = value.get("reset_at").and_then(Value::as_i64)?;
    normalize_window_values(slot, window_minutes, used_percent, resets_at, now)
}

fn normalize_window_values(
    slot: &str,
    window_minutes: u32,
    used_percent: f64,
    resets_at: i64,
    now: DateTime<Utc>,
) -> Option<QuotaWindow> {
    if window_minutes == 0
        || !used_percent.is_finite()
        || !(0.0..=100.0).contains(&used_percent)
        || resets_at <= 0
    {
        return None;
    }
    let resets_at = DateTime::<Utc>::from_timestamp(resets_at, 0)?;
    let remaining = 100.0 - used_percent;
    let remaining_percent = if resets_at > now {
        if (remaining - remaining.round()).abs() > f64::EPSILON {
            return None;
        }
        Some(remaining.round() as u8)
    } else {
        None
    };
    Some(QuotaWindow {
        id: format!("codex:{slot}:{window_minutes}"),
        name: window_name(window_minutes).to_string(),
        window_minutes,
        remaining_percent,
        resets_at: format_timestamp(resets_at),
        status: if resets_at > now {
            QuotaWindowStatus::Current
        } else {
            QuotaWindowStatus::PendingSync
        },
    })
}

fn window_name(minutes: u32) -> &'static str {
    match minutes {
        300 => "five_hour",
        10_080 => "weekly",
        _ => "custom",
    }
}

fn sanitize_credit(value: &Value) -> Option<ResetInventoryItem> {
    let id = value.get("id").and_then(Value::as_str)?.trim();
    if id.is_empty() || id.len() > 256 {
        return None;
    }
    let expires_at = value.get("expires_at").and_then(parse_value_timestamp)?;
    let title = value
        .get("title")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|title| !title.is_empty())
        .unwrap_or("Full reset");
    if title.chars().count() > 128 {
        return None;
    }
    Some(ResetInventoryItem {
        id: id.to_string(),
        title: title.to_string(),
        status: ResetItemStatus::Available,
        granted_at: value
            .get("granted_at")
            .and_then(parse_value_timestamp)
            .map(format_timestamp),
        expires_at: format_timestamp(expires_at),
    })
}

fn parse_direct_reset_inventory(value: &Value, now: DateTime<Utc>) -> Option<DirectResetInventory> {
    let available_count = value.get("available_count").and_then(Value::as_u64)?;
    let available_count = u32::try_from(available_count).ok()?;
    let credits = value.get("credits").and_then(Value::as_array)?;
    let mut cards = credits
        .iter()
        .filter(|credit| credit.get("status").and_then(Value::as_str) == Some("available"))
        .filter_map(|credit| {
            let granted_at = credit.get("granted_at").and_then(parse_value_timestamp)?;
            let expires_at = credit.get("expires_at").and_then(parse_value_timestamp)?;
            (expires_at > now).then(|| DirectResetCardTiming {
                granted_at: format_timestamp(granted_at),
                expires_at: format_timestamp(expires_at),
            })
        })
        .collect::<Vec<_>>();
    cards.sort_by(|left, right| left.expires_at.cmp(&right.expires_at));
    if cards.len() != available_count as usize {
        return None;
    }
    Some(DirectResetInventory {
        schema_version: 1,
        fetched_at: format_timestamp(now),
        available_count,
        cards,
    })
}

fn parse_direct_reset_response(
    status: u16,
    body: &Value,
    now: DateTime<Utc>,
) -> Result<DirectResetInventory, DirectResetRefreshError> {
    if status == 401 {
        return Err(DirectResetRefreshError::Unauthorized);
    }
    if !(200..300).contains(&status) {
        return Err(DirectResetRefreshError::HttpStatus(status));
    }
    parse_direct_reset_inventory(body, now).ok_or(DirectResetRefreshError::ResponseFormatChanged)
}

fn read_access_token(path: &Path) -> Result<Zeroizing<String>, DirectResetRefreshError> {
    let source = Zeroizing::new(
        fs::read_to_string(path).map_err(|_| DirectResetRefreshError::CredentialsUnavailable)?,
    );
    let mut auth = serde_json::from_str::<CodexAuthFile>(&source)
        .map_err(|_| DirectResetRefreshError::CredentialsUnavailable)?;
    let token = std::mem::take(&mut auth.tokens.access_token);
    if token.trim().is_empty() || token.len() > 16_384 {
        let mut token = token;
        token.zeroize();
        return Err(DirectResetRefreshError::CredentialsUnavailable);
    }
    Ok(Zeroizing::new(token))
}

fn fetch_direct_reset_inventory(
    access_token: &str,
    now: DateTime<Utc>,
) -> Result<DirectResetInventory, DirectResetRefreshError> {
    let client = direct_http_client()?;
    let mut authorization = HeaderValue::from_str(&format!("Bearer {access_token}"))
        .map_err(|_| DirectResetRefreshError::CredentialsUnavailable)?;
    authorization.set_sensitive(true);
    let response = client
        .get(DIRECT_RESET_ENDPOINT)
        .header(AUTHORIZATION, authorization)
        .send()
        .map_err(|_| DirectResetRefreshError::RequestFailed)?;
    let status = response.status().as_u16();
    if status == 401 {
        return Err(DirectResetRefreshError::Unauthorized);
    }
    if !(200..300).contains(&status) {
        return Err(DirectResetRefreshError::HttpStatus(status));
    }
    let body = response
        .json::<Value>()
        .map_err(|_| DirectResetRefreshError::ResponseFormatChanged)?;
    parse_direct_reset_response(status, &body, now)
}

fn fetch_direct_usage(
    access_token: &str,
    now: DateTime<Utc>,
) -> Result<DirectUsageSnapshot, DirectResetRefreshError> {
    let client = direct_http_client()?;
    let mut authorization = HeaderValue::from_str(&format!("Bearer {access_token}"))
        .map_err(|_| DirectResetRefreshError::CredentialsUnavailable)?;
    authorization.set_sensitive(true);
    let response = client
        .get("https://chatgpt.com/backend-api/wham/usage")
        .header(AUTHORIZATION, authorization)
        .send()
        .map_err(|_| DirectResetRefreshError::RequestFailed)?;
    if response.status().as_u16() == 401 {
        return Err(DirectResetRefreshError::Unauthorized);
    }
    if !response.status().is_success() {
        return Err(DirectResetRefreshError::HttpStatus(
            response.status().as_u16(),
        ));
    }
    let body = response
        .json::<Value>()
        .map_err(|_| DirectResetRefreshError::ResponseFormatChanged)?;
    let rate_limit = body
        .get("rate_limit")
        .and_then(Value::as_object)
        .ok_or(DirectResetRefreshError::ResponseFormatChanged)?;
    let windows = [
        ("primary", rate_limit.get("primary_window")),
        ("secondary", rate_limit.get("secondary_window")),
    ]
    .into_iter()
    .filter_map(|(slot, value)| value.and_then(|value| normalize_window(slot, value, now)))
    .collect();
    Ok(DirectUsageSnapshot {
        schema_version: 1,
        fetched_at: format_timestamp(now),
        windows,
    })
}

fn direct_http_client() -> Result<Client, DirectResetRefreshError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    Client::builder()
        .timeout(DIRECT_RESET_TIMEOUT)
        .user_agent(concat!("CodexQuota/", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|_| DirectResetRefreshError::RequestFailed)
}

fn next_freshness(
    previous: UpstreamDatasetFreshness,
    success: bool,
    has_cache: bool,
    now: DateTime<Utc>,
) -> UpstreamDatasetFreshness {
    UpstreamDatasetFreshness {
        status: if success {
            UpstreamFreshnessStatus::Current
        } else if has_cache {
            UpstreamFreshnessStatus::Cached
        } else {
            UpstreamFreshnessStatus::Unavailable
        },
        last_attempt_at: Some(format_timestamp(now)),
        last_success_at: if success {
            Some(format_timestamp(now))
        } else {
            previous.last_success_at
        },
    }
}

fn load_upstream_freshness(path: &Path) -> Option<UpstreamFreshness> {
    serde_json::from_str(&fs::read_to_string(path).ok()?).ok()
}

fn save_upstream_freshness(path: &Path, freshness: &UpstreamFreshness) -> Result<(), ()> {
    let parent = path.parent().ok_or(())?;
    fs::create_dir_all(parent).map_err(|_| ())?;
    fs::write(path, serde_json::to_vec(freshness).map_err(|_| ())?).map_err(|_| ())
}

fn save_direct_usage(
    path: &Path,
    usage: &DirectUsageSnapshot,
) -> Result<(), DirectResetRefreshError> {
    let parent = path
        .parent()
        .ok_or(DirectResetRefreshError::RequestFailed)?;
    fs::create_dir_all(parent).map_err(|_| DirectResetRefreshError::RequestFailed)?;
    fs::write(
        path,
        serde_json::to_vec(usage).map_err(|_| DirectResetRefreshError::ResponseFormatChanged)?,
    )
    .map_err(|_| DirectResetRefreshError::RequestFailed)
}

fn save_direct_reset_inventory(
    path: &Path,
    inventory: &DirectResetInventory,
) -> Result<(), DirectResetRefreshError> {
    let Some(parent) = path.parent() else {
        return Err(DirectResetRefreshError::RequestFailed);
    };
    fs::create_dir_all(parent).map_err(|_| DirectResetRefreshError::RequestFailed)?;
    let payload = serde_json::to_vec(inventory)
        .map_err(|_| DirectResetRefreshError::ResponseFormatChanged)?;
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, payload).map_err(|_| DirectResetRefreshError::RequestFailed)?;
    match fs::rename(&temporary, path) {
        Ok(()) => Ok(()),
        Err(_) => {
            let _ = fs::remove_file(&temporary);
            Err(DirectResetRefreshError::RequestFailed)
        }
    }
}

fn find_cached_json(
    root: &Path,
    endpoint: &[u8],
) -> Result<Option<CachedJson>, QuotaCollectorError> {
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let mut candidates = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !name.starts_with("data_") || !entry.file_type()?.is_file() {
            continue;
        }
        let bytes = fs::read(entry.path())?;
        if bytes.len() < BLOCK_HEADER_SIZE
            || bytes
                .get(0..4)
                .and_then(|v| v.try_into().ok())
                .map(u32::from_le_bytes)
                != Some(BLOCK_FILE_MAGIC)
        {
            continue;
        }
        let entry_size = bytes
            .get(12..16)
            .and_then(|v| v.try_into().ok())
            .map(i32::from_le_bytes);
        if entry_size != Some(ENTRY_STORE_SIZE as i32) {
            continue;
        }
        for offset in (BLOCK_HEADER_SIZE..bytes.len()).step_by(ENTRY_STORE_SIZE) {
            if offset + ENTRY_STORE_SIZE > bytes.len() || i32_at(&bytes, offset + 20) != Some(0) {
                continue;
            }
            let Some(key_len) =
                i32_at(&bytes, offset + 32).and_then(|value| usize::try_from(value).ok())
            else {
                continue;
            };
            if key_len == 0
                || key_len > INLINE_KEY_CAPACITY
                || offset + ENTRY_KEY_OFFSET + key_len > bytes.len()
            {
                continue;
            }
            let key = &bytes[offset + ENTRY_KEY_OFFSET..offset + ENTRY_KEY_OFFSET + key_len];
            if !key.windows(endpoint.len()).any(|window| window == endpoint) {
                continue;
            }
            if let Ok(Some(candidate)) = parse_cached_entry(root, &entry, &bytes, offset) {
                candidates.push(candidate);
            }
        }
    }
    candidates.sort_by(|left, right| left.cached_at.cmp(&right.cached_at));
    Ok(candidates.pop())
}

fn parse_cached_entry(
    root: &Path,
    entry: &fs::DirEntry,
    bytes: &[u8],
    offset: usize,
) -> Result<Option<CachedJson>, QuotaCollectorError> {
    let Some(metadata_size) =
        i32_at(bytes, offset + 40).and_then(|value| usize::try_from(value).ok())
    else {
        return Ok(None);
    };
    let Some(body_size) = i32_at(bytes, offset + 44).and_then(|value| usize::try_from(value).ok())
    else {
        return Ok(None);
    };
    let Some(metadata_address) = u32_at(bytes, offset + 56) else {
        return Ok(None);
    };
    let Some(body_address) = u32_at(bytes, offset + 60) else {
        return Ok(None);
    };
    let metadata = read_cache_address(root, metadata_address, metadata_size)?;
    let body = read_cache_address(root, body_address, body_size)?;
    let encoding =
        ascii_header(&metadata, "content-encoding").unwrap_or_else(|| "identity".to_string());
    let body = decompress_body(&body, &encoding)?;
    let Ok(body) = serde_json::from_slice::<Value>(&body) else {
        return Ok(None);
    };
    let cached_at = ascii_header(&metadata, "date")
        .and_then(|date| {
            DateTime::parse_from_rfc2822(&date)
                .ok()
                .map(|value| value.with_timezone(&Utc))
        })
        .or_else(|| {
            entry
                .metadata()
                .ok()
                .and_then(|metadata| metadata.modified().ok())
                .map(format_system_time)
        })
        .unwrap_or(DateTime::<Utc>::UNIX_EPOCH);
    Ok(Some(CachedJson { cached_at, body }))
}

fn read_cache_address(root: &Path, raw: u32, size: usize) -> Result<Vec<u8>, QuotaCollectorError> {
    if raw & 0x8000_0000 == 0 {
        return Err(
            io::Error::new(io::ErrorKind::InvalidData, "uninitialized cache address").into(),
        );
    }
    let kind = (raw >> 28) & 0x7;
    if kind == 0 {
        let external = raw & 0x0fff_ffff;
        for name in [format!("f_{external:06x}"), format!("f_{external:x}")] {
            if let Ok(bytes) = fs::read(root.join(name)) {
                return Ok(bytes.into_iter().take(size).collect());
            }
        }
        return Err(io::Error::new(io::ErrorKind::NotFound, "external cache file missing").into());
    }
    let block_size = match kind {
        1 => 36,
        2 => 256,
        3 => 1_024,
        4 => 4_096,
        _ => {
            return Err(
                io::Error::new(io::ErrorKind::InvalidData, "unsupported cache block type").into(),
            );
        }
    };
    let file = (raw >> 16) & 0xff;
    let start = (raw & 0xffff) as usize;
    let bytes = fs::read(root.join(format!("data_{file}")))?;
    let offset = BLOCK_HEADER_SIZE + start * block_size;
    if offset.checked_add(size).is_none_or(|end| end > bytes.len()) {
        return Err(
            io::Error::new(io::ErrorKind::InvalidData, "cache address out of bounds").into(),
        );
    }
    Ok(bytes[offset..offset + size].to_vec())
}

fn decompress_body(body: &[u8], encoding: &str) -> Result<Vec<u8>, QuotaCollectorError> {
    let mut output = Vec::new();
    match encoding.to_ascii_lowercase().as_str() {
        "br" => {
            Decompressor::new(body, 4_096).read_to_end(&mut output)?;
        }
        "gzip" => {
            GzDecoder::new(body).read_to_end(&mut output)?;
        }
        "deflate" => {
            if ZlibDecoder::new(body).read_to_end(&mut output).is_err() {
                output.clear();
                DeflateDecoder::new(body).read_to_end(&mut output)?;
            }
        }
        "identity" | "" => output.extend_from_slice(body),
        _ => {
            return Err(
                io::Error::new(io::ErrorKind::InvalidData, "unsupported content encoding").into(),
            );
        }
    }
    Ok(output)
}

fn ascii_header(metadata: &[u8], name: &str) -> Option<String> {
    let text = String::from_utf8_lossy(metadata);
    text.split(['\0', '\r', '\n']).find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case(name)
            .then(|| value.trim().to_string())
    })
}

fn i32_at(bytes: &[u8], offset: usize) -> Option<i32> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(i32::from_le_bytes)
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
}

fn parse_value_timestamp(value: &Value) -> Option<DateTime<Utc>> {
    if let Some(text) = value.as_str() {
        return DateTime::parse_from_rfc3339(text)
            .ok()
            .map(|value| value.with_timezone(&Utc));
    }
    value
        .as_i64()
        .and_then(|seconds| DateTime::<Utc>::from_timestamp(seconds, 0))
}

fn format_system_time(value: SystemTime) -> DateTime<Utc> {
    value.into()
}

fn format_timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Millis, true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs::{create_dir_all, write};
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn usage_windows_map_to_the_closed_quota_contract() {
        let now = DateTime::parse_from_rfc3339("2026-07-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let value = json!({
            "used_percent": 94,
            "limit_window_seconds": 604800,
            "reset_at": 1785258157
        });
        let window = normalize_window("primary", &value, now).expect("valid usage window");
        assert_eq!(window.name, "weekly");
        assert_eq!(window.remaining_percent, Some(6));
        assert_eq!(window.status, QuotaWindowStatus::Current);
    }

    #[test]
    fn five_hour_window_is_identified_by_duration_in_either_official_slot() {
        let now = DateTime::parse_from_rfc3339("2026-07-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let value = json!({
            "used_percent": 32,
            "limit_window_seconds": 18_000,
            "reset_at": 1785258157
        });

        for slot in ["primary", "secondary"] {
            let window = normalize_window(slot, &value, now).expect("valid five-hour window");
            assert_eq!(window.name, "five_hour");
            assert_eq!(window.window_minutes, 300);
            assert_eq!(window.remaining_percent, Some(68));
            assert_eq!(window.id, format!("codex:{slot}:300"));
        }
    }

    #[test]
    fn malformed_or_fractional_percentages_are_not_guessed() {
        let now = DateTime::parse_from_rfc3339("2026-07-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let value = json!({
            "used_percent": 94.5,
            "limit_window_seconds": 604800,
            "reset_at": 1785258157
        });
        assert!(normalize_window("primary", &value, now).is_none());
    }

    #[test]
    fn direct_reset_response_keeps_only_available_card_timing() {
        let now = DateTime::parse_from_rfc3339("2026-07-26T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let response = json!({
            "available_count": 1,
            "credits": [
                {
                    "id": "private-reset-card-id",
                    "status": "available",
                    "granted_at": "2026-07-25T09:00:00Z",
                    "expires_at": "2026-07-31T19:49:39.737Z",
                    "description": "must not leave Windows"
                },
                {
                    "id": "redeemed-card-id",
                    "status": "redeemed",
                    "granted_at": "2026-07-22T09:00:00Z",
                    "expires_at": "2026-07-29T09:00:00Z"
                }
            ]
        });

        let inventory =
            parse_direct_reset_inventory(&response, now).expect("valid direct reset response");
        assert_eq!(inventory.available_count, 1);
        assert_eq!(inventory.cards.len(), 1);
        assert_eq!(inventory.cards[0].granted_at, "2026-07-25T09:00:00.000Z");
        assert_eq!(inventory.cards[0].expires_at, "2026-07-31T19:49:39.737Z");
        let serialized = serde_json::to_string(&inventory).unwrap();
        assert!(!serialized.contains("private-reset-card-id"));
        assert!(!serialized.contains("must not leave Windows"));
    }

    #[test]
    fn direct_reset_401_is_reported_without_response_details() {
        let now = DateTime::parse_from_rfc3339("2026-07-26T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let result = parse_direct_reset_response(401, &json!({"error": "private detail"}), now);
        assert_eq!(result, Err(DirectResetRefreshError::Unauthorized));
        assert!(!result.unwrap_err().to_string().contains("private detail"));
    }

    #[test]
    fn direct_http_client_initializes_the_tls_provider() {
        assert!(direct_http_client().is_ok());
    }

    #[test]
    fn failed_upstream_confirmation_downgrades_existing_data_to_cached() {
        let now = DateTime::parse_from_rfc3339("2026-07-28T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let state = next_freshness(
            UpstreamDatasetFreshness {
                status: UpstreamFreshnessStatus::Current,
                last_attempt_at: Some("2026-07-28T09:45:00.000Z".to_string()),
                last_success_at: Some("2026-07-28T09:45:00.000Z".to_string()),
            },
            false,
            true,
            now,
        );
        assert_eq!(state.status, UpstreamFreshnessStatus::Cached);
        assert_eq!(
            state.last_success_at.as_deref(),
            Some("2026-07-28T09:45:00.000Z")
        );
        assert_eq!(
            state.last_attempt_at.as_deref(),
            Some("2026-07-28T10:00:00.000Z")
        );
    }

    #[test]
    fn direct_reset_cache_is_exposed_to_v1_without_card_identifiers() {
        let root = std::env::temp_dir().join(format!(
            "codex-quota-direct-reset-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let path = root.join(DIRECT_RESET_CACHE_FILE);
        let now = DateTime::parse_from_rfc3339("2026-07-26T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        save_direct_reset_inventory(
            &path,
            &DirectResetInventory {
                schema_version: 1,
                fetched_at: format_timestamp(now),
                available_count: 1,
                cards: vec![DirectResetCardTiming {
                    granted_at: "2026-07-25T09:00:00.000Z".to_string(),
                    expires_at: "2026-07-31T19:49:39.737Z".to_string(),
                }],
            },
        )
        .unwrap();
        let collector = QuotaCollector::new(CodexDataPaths {
            sessions_path: root.join("sessions"),
            reset_cache_path: None,
            auth_path: root.join("auth.json"),
            direct_reset_cache_path: path,
            direct_usage_cache_path: root.join(DIRECT_USAGE_CACHE_FILE),
            direct_freshness_cache_path: root.join(DIRECT_FRESHNESS_CACHE_FILE),
        });

        let snapshot = collector.collect(now).unwrap();
        assert_eq!(snapshot.reset_inventory.available_count, Some(1));
        assert_eq!(snapshot.reset_inventory.items[0].id, "reset-credit-1");
        assert_eq!(
            snapshot.reset_inventory.items[0].expires_at,
            "2026-07-31T19:49:39.737Z"
        );
        let wire = serde_json::to_string(&snapshot).unwrap();
        assert!(!wire.contains("granted_at"));
        assert!(!wire.contains("2026-07-25T09:00:00.000Z"));

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn package_discovery_accepts_codex_and_chatgpt_ids_only() {
        assert!(valid_chatgpt_package("OpenAI.Codex_2p2nqsd0c76g0"));
        assert!(valid_chatgpt_package("OpenAI.ChatGPT_abc123"));
        assert!(!valid_chatgpt_package("OpenAI.Codex_..\\Unexpected"));
    }

    #[test]
    fn collector_reads_usage_and_reset_inventory_from_chromium_cache() {
        let root = std::env::temp_dir().join(format!(
            "codex-quota-collector-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let cache = root.join("Cache_Data");
        create_dir_all(&cache).unwrap();
        let usage = br#"{"rate_limit":{"primary_window":{"used_percent":94,"limit_window_seconds":604800,"reset_at":1785258157},"secondary_window":null}}"#;
        let reset = br#"{"available_count":1,"credits":[{"id":"reset-1","title":"Full reset","status":"available","expires_at":"2026-07-31T19:49:39.737Z"}]}"#;
        let mut data = block_file(256, 32, 1);
        add_cache_entry(
            &mut data,
            1,
            3,
            15,
            b"1/0/_dk_https://chatgpt.com https://chatgpt.com https://chatgpt.com/backend-api/wham/usage",
            usage,
        );
        add_cache_entry(
            &mut data,
            2,
            4,
            16,
            b"1/0/_dk_https://chatgpt.com https://chatgpt.com https://chatgpt.com/backend-api/wham/rate-limit-reset-credits",
            reset,
        );
        write(cache.join("data_1"), data).unwrap();

        let collector = QuotaCollector::new(CodexDataPaths {
            sessions_path: root.join("sessions"),
            reset_cache_path: Some(cache),
            auth_path: root.join("auth.json"),
            direct_reset_cache_path: root.join("reset-credit-details-v1.json"),
            direct_usage_cache_path: root.join(DIRECT_USAGE_CACHE_FILE),
            direct_freshness_cache_path: root.join(DIRECT_FRESHNESS_CACHE_FILE),
        });
        let now = DateTime::parse_from_rfc3339("2026-07-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let snapshot = collector.collect(now).unwrap();
        assert_eq!(snapshot.source_status, QuotaSourceStatus::Ok);
        assert_eq!(snapshot.windows[0].remaining_percent, Some(6));
        assert_eq!(snapshot.windows[0].name, "weekly");
        assert_eq!(snapshot.reset_inventory.available_count, Some(1));
        assert_eq!(snapshot.reset_inventory.items[0].id, "reset-1");
        assert_eq!(snapshot.link.codex, CodexLinkStatus::Ok);

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn trusted_snapshot_cache_round_trips_as_explicit_stale_data() {
        let root = std::env::temp_dir().join(format!(
            "codex-quota-snapshot-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let path = root.join("last-snapshot-v1.json");
        let now = DateTime::parse_from_rfc3339("2026-07-24T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let snapshot = QuotaSnapshot {
            protocol_version: 1,
            generated_at: format_timestamp(now),
            source_status: QuotaSourceStatus::Ok,
            limits_collected_at: Some(format_timestamp(now)),
            windows: vec![],
            reset_inventory: ResetInventorySnapshot {
                status: ResetInventoryStatus::Cached,
                available_count: Some(0),
                cached_at: Some(format_timestamp(now)),
                items: vec![],
            },
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: CodexLinkStatus::Ok,
            },
            upstream_freshness: UpstreamFreshness::default(),
        };
        save_cached_snapshot(&path, &snapshot).unwrap();
        let restored = load_cached_snapshot(&path, now).expect("cached snapshot");
        assert_eq!(restored.source_status, QuotaSourceStatus::Partial);
        assert_eq!(restored.link.codex, CodexLinkStatus::Stale);
        assert_eq!(
            restored.reset_inventory.status,
            ResetInventoryStatus::CachedDerived
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn upstream_confirmation_error_codes_are_safe_and_actionable() {
        assert_eq!(
            UpstreamConfirmationErrorCode::from_refresh_error(
                DirectResetRefreshError::CredentialsUnavailable
            )
            .diagnostic_label(),
            "AUTH_UNAVAILABLE"
        );
        assert_eq!(
            UpstreamConfirmationErrorCode::from_refresh_error(
                DirectResetRefreshError::Unauthorized
            )
            .diagnostic_label(),
            "AUTH_REJECTED"
        );
        assert_eq!(
            UpstreamConfirmationErrorCode::from_refresh_error(DirectResetRefreshError::HttpStatus(
                429
            ))
            .diagnostic_label(),
            "UPSTREAM_HTTP"
        );
        assert!(
            !UpstreamConfirmationErrorCode::from_refresh_error(
                DirectResetRefreshError::ResponseFormatChanged
            )
            .diagnostic_label()
            .contains("token")
        );
    }

    fn block_file(entry_size: usize, blocks: usize, file_number: u16) -> Vec<u8> {
        let mut buffer = vec![0_u8; BLOCK_HEADER_SIZE + entry_size * blocks];
        buffer[0..4].copy_from_slice(&BLOCK_FILE_MAGIC.to_le_bytes());
        buffer[8..10].copy_from_slice(&file_number.to_le_bytes());
        buffer[12..16].copy_from_slice(&(entry_size as i32).to_le_bytes());
        buffer[20..24].copy_from_slice(&(blocks as i32).to_le_bytes());
        buffer
    }

    fn add_cache_entry(
        data: &mut [u8],
        entry_block: usize,
        metadata_block: usize,
        body_block: usize,
        key: &[u8],
        body: &[u8],
    ) {
        let offset = BLOCK_HEADER_SIZE + entry_block * ENTRY_STORE_SIZE;
        data[offset + 20..offset + 24].copy_from_slice(&0_i32.to_le_bytes());
        data[offset + 32..offset + 36].copy_from_slice(&(key.len() as i32).to_le_bytes());
        data[offset + 40..offset + 44].copy_from_slice(&(92_i32).to_le_bytes());
        data[offset + 44..offset + 48].copy_from_slice(&(body.len() as i32).to_le_bytes());
        data[offset + 56..offset + 60]
            .copy_from_slice(&cache_address(2, 1, metadata_block).to_le_bytes());
        data[offset + 60..offset + 64]
            .copy_from_slice(&cache_address(2, 1, body_block).to_le_bytes());
        data[offset + ENTRY_KEY_OFFSET..offset + ENTRY_KEY_OFFSET + key.len()].copy_from_slice(key);
        let metadata =
            b"HTTP/1.1 200\0date:Fri, 24 Jul 2026 10:00:00 GMT\0content-encoding:identity\0";
        let metadata_offset = BLOCK_HEADER_SIZE + metadata_block * ENTRY_STORE_SIZE;
        data[metadata_offset..metadata_offset + metadata.len()].copy_from_slice(metadata);
        let body_offset = BLOCK_HEADER_SIZE + body_block * ENTRY_STORE_SIZE;
        data[body_offset..body_offset + body.len()].copy_from_slice(body);
    }

    fn cache_address(kind: u32, file: u32, start: usize) -> u32 {
        0x8000_0000 | (kind << 28) | (file << 16) | start as u32
    }
}
