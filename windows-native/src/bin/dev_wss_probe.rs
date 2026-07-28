use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use codex_quota_windows_core::network::{SyncPayload, WssService};
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, PairingManager, QuotaLink, QuotaSnapshot,
    QuotaSourceStatus, ResetInventorySnapshot, ResetInventoryStatus, TaskSyncSnapshot,
    TlsIdentityKey, UpstreamFreshness,
};
use std::net::Ipv4Addr;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::TcpListener;
use tokio::sync::watch;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let address: Ipv4Addr = std::env::args()
        .nth(1)
        .ok_or("usage: dev_wss_probe <private-ipv4>")?
        .parse()?;
    if !(address.is_private() || address.is_link_local()) {
        return Err("address must be private or link-local".into());
    }

    let identity = TlsIdentityKey::generate().map_err(|_| "identity generation failed")?;
    let fingerprint = identity
        .public_key_fingerprint()
        .map_err(|_| "fingerprint failed")?;
    let service = WssService::new(
        &identity,
        PairingManager::new(fingerprint),
        unavailable_payload(),
    )
    .map_err(|_| "service creation failed")?;
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 17322)).await?;
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let server = tokio::spawn(service.clone().serve(listener, shutdown_rx));

    let offer = service
        .begin_pairing(now_ms())
        .await
        .map_err(|_| "pairing offer failed")?
        .to_qr_offer(vec![format!("wss://{address}:17322/pair")])
        .map_err(|_| "pairing endpoint failed")?;
    let offer_json = serde_json::to_vec(&offer)?;
    let deep_link = format!(
        "codexquota://pair?offer={}",
        URL_SAFE_NO_PAD.encode(offer_json)
    );
    println!("PAIR_LINK={deep_link}");

    let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
    let mut paired = false;
    loop {
        if !paired && service.phone_token_hash().await.is_some() {
            paired = true;
            println!("PAIR_OK");
        }
        if service.active_sync_connections() > 0 {
            println!("SYNC_OK");
            shutdown_tx.send_replace(true);
            server.await??;
            return Ok(());
        }
        if tokio::time::Instant::now() >= deadline {
            shutdown_tx.send_replace(true);
            server.await??;
            return Err("timed out waiting for Android".into());
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn unavailable_payload() -> SyncPayload {
    SyncPayload {
        quota: QuotaSnapshot {
            protocol_version: 1,
            generated_at: "2026-07-24T00:00:00Z".to_string(),
            source_status: QuotaSourceStatus::Unavailable,
            limits_collected_at: None,
            windows: vec![],
            reset_inventory: ResetInventorySnapshot {
                status: ResetInventoryStatus::Unavailable,
                available_count: None,
                cached_at: None,
                items: vec![],
            },
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: CodexLinkStatus::Unavailable,
            },
            upstream_freshness: UpstreamFreshness::default(),
        },
        tasks: TaskSyncSnapshot {
            protocol_version: 1,
            sequence: 0,
            generated_at_ms: now_ms(),
            chat_gpt_state: ChatGptState::HookUnavailable,
            chat_gpt_focused: false,
            tasks: vec![],
        },
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}
