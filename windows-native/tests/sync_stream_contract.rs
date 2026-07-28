use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus,
    QuotaWindow, QuotaWindowStatus, ResetInventorySnapshot, ResetInventoryStatus, SyncStreamFrame,
    TaskSyncSnapshot, UpstreamFreshness,
};

#[test]
fn server_hello_negotiates_transport_and_nested_contract_versions() {
    let frame = SyncStreamFrame::ServerHello {
        transport_version: 1,
        connection_id: "connection_0123456789".to_string(),
        quota_version: 1,
        task_version: 1,
        heartbeat_interval_ms: 15_000,
    };

    let json = serde_json::to_value(frame).expect("serializable server hello");

    assert_eq!(json["type"], "server_hello");
    assert_eq!(json["transportVersion"], 1);
    assert_eq!(json["quotaVersion"], 1);
    assert_eq!(json["taskVersion"], 1);
    assert_eq!(json["heartbeatIntervalMs"], 15_000);
}

#[test]
fn combined_snapshot_contains_only_the_two_minimized_nested_contracts() {
    let quota = QuotaSnapshot {
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
    };
    let tasks = TaskSyncSnapshot {
        protocol_version: 1,
        sequence: 3,
        generated_at_ms: 1_784_880_000_000,
        chat_gpt_state: ChatGptState::Running,
        chat_gpt_focused: false,
        tasks: vec![],
    };
    let frame = SyncStreamFrame::Snapshot {
        transport_version: 1,
        connection_id: "connection_0123456789".to_string(),
        sequence: 8,
        generated_at_ms: 1_784_880_000_000,
        quota,
        tasks,
    };

    let json = serde_json::to_value(frame).expect("serializable sync snapshot");
    let wire = json.to_string();

    assert_eq!(json["type"], "snapshot");
    assert_eq!(json["quota"]["windows"][0]["remainingPercent"], 67);
    assert_eq!(json["tasks"]["chatGptState"], "running");
    for forbidden in ["prompt", "response", "command", "path", "token", "cookie"] {
        assert!(
            !wire.contains(forbidden),
            "unexpected private field: {forbidden}"
        );
    }
}
