use codex_quota_windows_core::host::{HostPaths, WindowsHost};
use codex_quota_windows_core::network::SyncPayload;
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus,
    ResetInventorySnapshot, ResetInventoryStatus, TaskSyncSnapshot, UpstreamFreshness,
};
use std::fs;
use std::net::{Ipv4Addr, SocketAddr};

#[tokio::test]
async fn host_reuses_identity_and_builds_a_closed_android_pairing_link() {
    let directory = std::env::temp_dir().join(format!(
        "codex-quota-host-{}-{}",
        std::process::id(),
        now_ms()
    ));
    let paths = HostPaths::in_data_directory(&directory);
    let first = WindowsHost::start(
        paths.clone(),
        SocketAddr::from((Ipv4Addr::LOCALHOST, 0)),
        unavailable_payload(),
    )
    .await
    .expect("start first host");
    let fingerprint = first.fingerprint_hex().to_string();
    let presentation = first
        .begin_pairing(now_ms(), [Ipv4Addr::new(192, 168, 1, 42)])
        .await
        .expect("pairing presentation");
    assert!(
        presentation
            .deep_link
            .starts_with("codexquota://pair?offer=")
    );
    assert_eq!(presentation.offer.computer_fingerprint, fingerprint);
    assert_eq!(presentation.offer.endpoints.len(), 1);
    first.shutdown().await.expect("shutdown first host");

    let second = WindowsHost::start(
        paths,
        SocketAddr::from((Ipv4Addr::LOCALHOST, 0)),
        unavailable_payload(),
    )
    .await
    .expect("start second host");
    assert_eq!(second.fingerprint_hex(), fingerprint);
    second.shutdown().await.expect("shutdown second host");
    let _ = fs::remove_dir_all(directory);
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
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64
}
