use codex_quota_windows_core::{ChatGptState, TaskReducer};

#[test]
fn windows_emits_the_android_task_snapshot_contract_without_private_hook_fields() {
    let mut reducer = TaskReducer::new();
    reducer
        .ingest_json_with_title(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "整理今天的素材 token=sk-private"
            }"#,
            1_721_800_000_000,
            Some("整理今天的素材"),
        )
        .expect("valid hook event");

    let snapshot = reducer.sync_snapshot(42, 1_721_800_000_100, ChatGptState::Running, false);
    let json = serde_json::to_value(snapshot).expect("serializable task snapshot");

    assert_eq!(json["protocolVersion"], 1);
    assert_eq!(json["sequence"], 42);
    assert_eq!(json["chatGptState"], "running");
    assert_eq!(json["chatGptFocused"], false);
    assert_eq!(json["tasks"][0]["conversationId"], "session-42");
    assert_eq!(json["tasks"][0]["title"], "整理今天的素材");
    assert!(json["tasks"][0].get("turnId").is_none());
    assert!(!json.to_string().contains("sk-private"));
    assert!(!json.to_string().contains("prompt"));
}
