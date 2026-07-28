use codex_quota_windows_core::{SafeActivity, TaskReducer, TaskState};

#[test]
fn user_prompt_submit_starts_the_session_task() {
    let mut reducer = TaskReducer::new();
    let update = reducer
        .ingest_json(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "整理今天的素材"
            }"#,
            1_721_800_000_000,
        )
        .expect("valid official hook event")
        .expect("task update");

    assert_eq!(update.conversation_id, "session-42");
    assert_eq!(update.turn_id, "turn-7");
    assert_eq!(update.state, TaskState::Running);
    assert_eq!(update.updated_at_ms, 1_721_800_000_000);
}

#[test]
fn permission_request_marks_the_session_as_needing_authorization() {
    let mut reducer = TaskReducer::new();
    let update = reducer
        .ingest_json(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "PermissionRequest",
                "tool_name": "Bash",
                "tool_input": {
                    "command": "private command must not escape",
                    "description": "private approval reason"
                }
            }"#,
            1_721_800_001_000,
        )
        .expect("valid official hook event")
        .expect("task update");

    assert_eq!(update.state, TaskState::NeedsAuthorization);
}

#[test]
fn stop_only_marks_the_turn_as_waiting_for_review() {
    let mut reducer = TaskReducer::new();
    let update = reducer
        .ingest_json(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "Stop",
                "stop_hook_active": false,
                "last_assistant_message": "This text must not be classified."
            }"#,
            1_721_800_002_000,
        )
        .expect("valid official hook event")
        .expect("task update");

    assert_eq!(update.state, TaskState::WaitingForReview);
}

#[test]
fn tool_events_emit_only_an_allowlisted_activity_summary() {
    let mut reducer = TaskReducer::new();
    let update = reducer
        .ingest_json(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "PreToolUse",
                "tool_name": "apply_patch",
                "tool_use_id": "call-secret",
                "tool_input": {
                    "command": "*** Update File: C:\\private\\project.txt\n+secret"
                }
            }"#,
            1_721_800_003_000,
        )
        .expect("valid official hook event")
        .expect("task update");

    assert_eq!(update.state, TaskState::Running);
    assert_eq!(update.activity, Some(SafeActivity::ModifyingFiles));

    let outbound = serde_json::to_string(&update).expect("serializable task contract");
    assert!(!outbound.contains("private"));
    assert!(!outbound.contains("project.txt"));
    assert!(!outbound.contains("call-secret"));
    assert!(!outbound.contains("secret"));
}

#[test]
fn follow_up_turns_update_one_conversation_record() {
    let mut reducer = TaskReducer::new();
    for (turn_id, observed_at_ms) in [("turn-1", 1_000), ("turn-2", 2_000)] {
        reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "session-42",
                        "turn_id": "{turn_id}",
                        "hook_event_name": "UserPromptSubmit",
                        "prompt": "继续"
                    }}"#
                ),
                observed_at_ms,
            )
            .expect("valid official hook event");
    }

    let tasks = reducer.tasks();
    assert_eq!(tasks.len(), 1);
    assert_eq!(tasks[0].conversation_id, "session-42");
    assert_eq!(tasks[0].turn_id, "turn-2");
    assert_eq!(tasks[0].updated_at_ms, 2_000);
}

#[test]
fn indexed_titles_drop_urls_and_suspected_secrets_before_storage() {
    let mut reducer = TaskReducer::new();
    let update = reducer
        .ingest_json_with_title(
            r#"{
                "session_id": "session-privacy",
                "turn_id": "turn-1",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "private prompt must be ignored"
            }"#,
            3_000,
            Some("整理今天素材\nhttps://private.example/path token=sk-secret-value 并生成发布提纲草案"),
        )
        .expect("valid official hook event")
        .expect("task update");

    assert_eq!(update.title, "整理今天素材 并生成发布提纲草案");
    assert!(update.title.chars().count() <= 16);

    let outbound = serde_json::to_string(&update).expect("serializable task contract");
    assert!(!outbound.contains("private.example"));
    assert!(!outbound.contains("sk-secret-value"));
}

#[test]
fn follow_up_events_keep_the_indexed_conversation_title() {
    let mut reducer = TaskReducer::new();
    reducer
        .ingest_json_with_title(
            r#"{
                "session_id": "session-title",
                "turn_id": "turn-1",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "ignored"
            }"#,
            1_000,
            Some("制作小米手环额度应用"),
        )
        .expect("first turn");
    let update = reducer
        .ingest_json(
            r#"{
                "session_id": "session-title",
                "turn_id": "turn-2",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "继续"
            }"#,
            2_000,
        )
        .expect("follow-up turn")
        .expect("task update");

    assert_eq!(update.title, "制作小米手环额度应用");
}

#[test]
fn tool_names_are_reduced_to_a_closed_activity_allowlist() {
    let cases = [
        ("Bash", Some(SafeActivity::ExecutingCommand)),
        ("apply_patch", Some(SafeActivity::ModifyingFiles)),
        ("mcp__browser__navigate", Some(SafeActivity::UsingBrowser)),
        ("mcp__private_vendor__unknown", None),
    ];

    for (index, (tool_name, expected)) in cases.into_iter().enumerate() {
        let mut reducer = TaskReducer::new();
        let update = reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "session-{index}",
                        "turn_id": "turn-1",
                        "hook_event_name": "PreToolUse",
                        "tool_name": "{tool_name}",
                        "tool_use_id": "private-call-id",
                        "tool_input": {{"command": "private input"}}
                    }}"#
                ),
                4_000,
            )
            .expect("valid official hook event")
            .expect("tool activity update");

        assert_eq!(update.state, TaskState::Running);
        assert_eq!(update.activity, expected);
        let outbound = serde_json::to_string(&update).expect("serializable task contract");
        assert!(!outbound.contains(tool_name));
        assert!(!outbound.contains("private"));
    }
}

#[test]
fn band_board_keeps_three_tasks_by_state_priority_then_recency() {
    let mut reducer = TaskReducer::new();
    let events = [
        ("old-waiting", "Stop", 1_000),
        ("running", "UserPromptSubmit", 2_000),
        ("authorization", "PermissionRequest", 3_000),
        ("new-waiting", "Stop", 4_000),
    ];
    for (session_id, event_name, observed_at_ms) in events {
        reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "{session_id}",
                        "turn_id": "turn-1",
                        "hook_event_name": "{event_name}",
                        "prompt": "测试任务"
                    }}"#
                ),
                observed_at_ms,
            )
            .expect("valid official hook event");
    }

    let ids: Vec<_> = reducer
        .band_tasks()
        .into_iter()
        .map(|task| task.conversation_id.as_str())
        .collect();
    assert_eq!(ids, ["authorization", "running", "new-waiting"]);
}

#[test]
fn phone_board_keeps_all_active_and_only_ten_latest_waiting_tasks() {
    let mut reducer = TaskReducer::new();
    for index in 0..12 {
        reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "waiting-{index:02}",
                        "turn_id": "turn-1",
                        "hook_event_name": "Stop"
                    }}"#
                ),
                index,
            )
            .expect("waiting event");
    }
    for (session_id, event_name) in [
        ("running", "UserPromptSubmit"),
        ("authorization", "PermissionRequest"),
    ] {
        reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "{session_id}",
                        "turn_id": "turn-1",
                        "hook_event_name": "{event_name}",
                        "prompt": "活动任务"
                    }}"#
                ),
                100,
            )
            .expect("active event");
    }

    let ids: Vec<_> = reducer
        .phone_tasks()
        .into_iter()
        .map(|task| task.conversation_id.as_str())
        .collect();
    assert_eq!(ids.len(), 12);
    assert!(ids.contains(&"running"));
    assert!(ids.contains(&"authorization"));
    assert!(!ids.contains(&"waiting-00"));
    assert!(!ids.contains(&"waiting-01"));
    assert!(ids.contains(&"waiting-11"));
}

#[test]
fn hidden_tasks_leave_both_boards_and_reappear_on_new_activity() {
    let mut reducer = TaskReducer::new();
    reducer
        .ingest_json(
            r#"{
                "session_id": "session-hidden",
                "turn_id": "turn-1",
                "hook_event_name": "PermissionRequest"
            }"#,
            1_000,
        )
        .expect("initial event");

    assert!(reducer.hide_task("session-hidden"));
    assert!(reducer.phone_tasks().is_empty());
    assert!(reducer.band_tasks().is_empty());

    reducer
        .ingest_json(
            r#"{
                "session_id": "session-hidden",
                "turn_id": "turn-2",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "继续处理"
            }"#,
            2_000,
        )
        .expect("new event");

    assert_eq!(reducer.phone_tasks().len(), 1);
    assert_eq!(reducer.band_tasks().len(), 1);
}

#[test]
fn cleanup_only_deletes_waiting_for_review_records() {
    let mut reducer = TaskReducer::new();
    for (session_id, event_name) in [
        ("waiting-one", "Stop"),
        ("waiting-two", "Stop"),
        ("running", "UserPromptSubmit"),
        ("authorization", "PermissionRequest"),
    ] {
        reducer
            .ingest_json(
                &format!(
                    r#"{{
                        "session_id": "{session_id}",
                        "turn_id": "turn-1",
                        "hook_event_name": "{event_name}",
                        "prompt": "任务"
                    }}"#
                ),
                1_000,
            )
            .expect("task event");
    }

    assert!(!reducer.delete_waiting("running"));
    assert!(reducer.delete_waiting("waiting-one"));
    assert_eq!(reducer.clear_waiting(), 1);

    let remaining: Vec<_> = reducer
        .tasks()
        .into_iter()
        .map(|task| task.conversation_id.as_str())
        .collect();
    assert!(remaining.contains(&"running"));
    assert!(remaining.contains(&"authorization"));
    assert!(!remaining.contains(&"waiting-one"));
    assert!(!remaining.contains(&"waiting-two"));
}

#[test]
fn hook_ingress_rejects_unbounded_or_missing_identity_fields() {
    let mut reducer = TaskReducer::new();
    for invalid in [
        r#"{"session_id":"","turn_id":"turn-1","hook_event_name":"Stop"}"#.to_string(),
        r#"{"session_id":"session-1","turn_id":"","hook_event_name":"Stop"}"#.to_string(),
        format!(
            r#"{{"session_id":"{}","turn_id":"turn-1","hook_event_name":"Stop"}}"#,
            "x".repeat(129)
        ),
    ] {
        assert!(reducer.ingest_json(&invalid, 1_000).is_err());
    }

    let oversized = format!(
        r#"{{"session_id":"session-1","turn_id":"turn-1","hook_event_name":"UserPromptSubmit","prompt":"{}"}}"#,
        "x".repeat(256 * 1024)
    );
    assert!(reducer.ingest_json(&oversized, 1_000).is_err());
    assert!(
        reducer
            .ingest_json(
                r#"{"session_id":"session-1","turn_id":"turn-1","hook_event_name":"Stop"}"#,
                -1,
            )
            .is_err()
    );
}
