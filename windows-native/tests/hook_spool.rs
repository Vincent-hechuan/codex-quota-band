use codex_quota_windows_core::hook::{
    HookDiagnosticOutcome, HookDiagnosticStore, HookEventSpool, HookTaskRuntime,
};
use codex_quota_windows_core::{ChatGptState, SafeActivity, TaskReducer, TaskState};
use std::fs;

#[test]
fn hook_spool_uses_the_latest_chatgpt_task_name_instead_of_the_prompt() {
    let directory = temporary_directory("thread-title");
    let index_path = directory.join("session_index.jsonl");
    fs::create_dir_all(&directory).expect("create test directory");
    fs::write(
        &index_path,
        concat!(
            "{\"id\":\"session-title\",\"thread_name\":\"旧任务名\",\"updated_at\":\"2026-07-24T00:00:00Z\"}\n",
            "{\"id\":\"session-title\",\"thread_name\":\"手机codex额度开发\",\"updated_at\":\"2026-07-25T00:00:00Z\"}\n"
        ),
    )
    .expect("write thread index");
    let spool = HookEventSpool::with_thread_index(&directory, &index_path);

    spool
        .enqueue_json(
            r#"{"session_id":"session-title","turn_id":"turn-1","hook_event_name":"UserPromptSubmit","prompt":"接下来要做什么"}"#,
            1_000,
        )
        .expect("enqueue prompt event");

    let updates = spool.drain_updates().expect("drain task updates");
    assert_eq!(updates.len(), 1);
    assert_eq!(updates[0].title, "手机codex额度开发");
    assert!(
        !serde_json::to_string(&updates[0])
            .unwrap()
            .contains("接下来要做什么")
    );
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn later_hook_events_replace_a_generic_title_after_chatgpt_names_the_task() {
    let directory = temporary_directory("delayed-thread-title");
    let index_path = directory.join("session_index.jsonl");
    fs::create_dir_all(&directory).expect("create test directory");
    let spool = HookEventSpool::with_thread_index(&directory, &index_path);

    spool
        .enqueue_json(
            r#"{"session_id":"session-delayed","turn_id":"turn-1","hook_event_name":"UserPromptSubmit","prompt":"帮我看看这个"}"#,
            1_000,
        )
        .expect("enqueue unnamed task");
    fs::write(
        &index_path,
        "{\"id\":\"session-delayed\",\"thread_name\":\"额度同步故障排查\",\"updated_at\":\"2026-07-25T00:00:00Z\"}\n",
    )
    .expect("write delayed title");
    spool
        .enqueue_json(
            r#"{"session_id":"session-delayed","turn_id":"turn-1","hook_event_name":"PreToolUse","tool_name":"Bash"}"#,
            2_000,
        )
        .expect("enqueue later tool event");

    let mut updates = spool.drain_updates().expect("drain task updates");
    updates.sort_by_key(|update| update.updated_at_ms);
    let mut reducer = TaskReducer::new();
    for update in updates {
        reducer.ingest_update(update).expect("trusted spool update");
    }
    assert_eq!(reducer.tasks()[0].title, "额度同步故障排查");
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn runtime_refreshes_a_generic_title_when_the_index_arrives_without_a_new_hook_event() {
    let directory = temporary_directory("indexed-title-refresh");
    let index_path = directory.join("session_index.jsonl");
    fs::create_dir_all(&directory).expect("create test directory");
    let spool = HookEventSpool::with_thread_index(&directory, &index_path);

    spool
        .enqueue_json(
            r#"{"session_id":"session-refresh","turn_id":"turn-1","hook_event_name":"UserPromptSubmit","prompt":"后台处理"}"#,
            1_000,
        )
        .expect("enqueue unnamed task");

    let mut runtime = HookTaskRuntime::new();
    let first = runtime
        .poll(&spool, 1_100, false)
        .expect("initial poll")
        .expect("initial snapshot");
    assert_eq!(first.tasks[0].title, "任务");

    fs::write(
        &index_path,
        "{\"id\":\"session-refresh\",\"thread_name\":\"后台任务标题\"}\n",
    )
    .expect("write delayed thread index");

    let refreshed = runtime
        .poll(&spool, 1_200, false)
        .expect("refresh poll")
        .expect("title refresh snapshot");
    assert_eq!(refreshed.tasks[0].title, "后台任务标题");
    assert_eq!(refreshed.sequence, 2);
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn hook_spool_persists_only_the_sanitized_task_update() {
    let directory = temporary_directory("privacy");
    let spool = HookEventSpool::new(&directory);
    spool
        .enqueue_json(
            r#"{
                "session_id": "session-42",
                "turn_id": "turn-7",
                "hook_event_name": "UserPromptSubmit",
                "prompt": "整理今天素材 https://private.example token=sk-private-value",
                "tool_input": {"command": "C:\\private\\project.txt"}
            }"#,
            1_721_800_000_000,
        )
        .expect("enqueue sanitized event");

    let files = fs::read_dir(&directory)
        .expect("spool directory")
        .map(|entry| entry.expect("spool entry").path())
        .collect::<Vec<_>>();
    assert_eq!(files.len(), 1);
    let persisted = fs::read_to_string(&files[0]).expect("persisted update");
    assert!(!persisted.contains("private.example"));
    assert!(!persisted.contains("sk-private-value"));
    assert!(!persisted.contains("project.txt"));
    assert!(!persisted.contains("prompt"));
    assert!(!persisted.contains("tool_input"));

    let updates = spool.drain_updates().expect("drain task updates");
    assert_eq!(updates.len(), 1);
    assert_eq!(updates[0].conversation_id, "session-42");
    assert_eq!(updates[0].title, "任务");
    assert_eq!(updates[0].state, TaskState::Running);
    assert!(
        fs::read_dir(&directory)
            .expect("empty spool")
            .next()
            .is_none()
    );
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn reducer_preserves_the_private_safe_title_across_tool_events() {
    let directory = temporary_directory("sequence");
    let index_path = directory.join("session_index.jsonl");
    fs::create_dir_all(&directory).expect("create test directory");
    fs::write(
        &index_path,
        "{\"id\":\"session-7\",\"thread_name\":\"制作手环任务面板\"}\n",
    )
    .expect("write thread index");
    let spool = HookEventSpool::with_thread_index(&directory, &index_path);
    spool
        .enqueue_json(
            r#"{"session_id":"session-7","turn_id":"turn-1","hook_event_name":"UserPromptSubmit","prompt":"制作手环任务面板"}"#,
            1_000,
        )
        .expect("prompt event");
    spool
        .enqueue_json(
            r#"{"session_id":"session-7","turn_id":"turn-1","hook_event_name":"PreToolUse","tool_name":"apply_patch","tool_input":{"command":"private"}}"#,
            2_000,
        )
        .expect("tool event");

    let mut updates = spool.drain_updates().expect("ordered updates");
    updates.sort_by_key(|update| update.updated_at_ms);
    let mut reducer = TaskReducer::new();
    for update in updates {
        reducer.ingest_update(update).expect("trusted spool update");
    }
    let task = reducer.tasks()[0];
    assert_eq!(task.title, "制作手环任务面板");
    assert_eq!(task.state, TaskState::Running);
    assert_eq!(task.activity, Some(SafeActivity::ModifyingFiles));
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn task_runtime_publishes_only_when_the_spool_changes() {
    let directory = temporary_directory("runtime");
    let spool = HookEventSpool::new(&directory);
    spool
        .enqueue_json(
            r#"{"session_id":"session-runtime","turn_id":"turn-1","hook_event_name":"PermissionRequest","tool_name":"Bash"}"#,
            3_000,
        )
        .expect("permission event");

    let mut runtime = HookTaskRuntime::new();
    let first = runtime
        .poll(&spool, 3_100, false)
        .expect("runtime poll")
        .expect("changed task snapshot");
    assert_eq!(first.sequence, 1);
    assert_eq!(first.chat_gpt_state, ChatGptState::Running);
    assert!(!first.chat_gpt_focused);
    assert_eq!(first.tasks.len(), 1);
    assert_eq!(first.tasks[0].state, TaskState::NeedsAuthorization);
    assert!(
        runtime
            .poll(&spool, 3_200, true)
            .expect("empty poll")
            .is_none()
    );
    let _ = fs::remove_dir_all(directory);
}

#[test]
fn hook_diagnostic_records_only_bounded_status_metadata() {
    let directory = temporary_directory("diagnostic");
    let path = directory.join("hook-status-v1.json");
    let store = HookDiagnosticStore::new(&path);
    store
        .record(4_000, HookDiagnosticOutcome::Invoked)
        .expect("write invoked status");
    let persisted = fs::read_to_string(&path).expect("diagnostic status");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&persisted).unwrap(),
        serde_json::json!({
            "protocolVersion": 1,
            "observedAtMs": 4_000,
            "outcome": "invoked"
        })
    );
    assert!(!persisted.contains("prompt"));
    assert!(!persisted.contains("command"));
    let _ = fs::remove_dir_all(directory);
}

fn temporary_directory(name: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!(
        "codex-quota-hook-spool-{name}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("system time")
            .as_nanos()
    ))
}
