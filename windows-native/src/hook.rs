use crate::{ChatGptState, TaskReducer, TaskSyncSnapshot, TaskUpdate};
use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_SPOOL_UPDATE_BYTES: u64 = 16 * 1024;
const MAX_THREAD_INDEX_BYTES: u64 = 8 * 1024 * 1024;
const OWNER_MARKER: &str = "--owner codex-quota";
const TASK_HOOK_EVENTS: [&str; 4] = [
    "UserPromptSubmit",
    "PermissionRequest",
    "PreToolUse",
    "Stop",
];
static EVENT_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug)]
pub enum HookSpoolError {
    Io(io::Error),
    InvalidEvent(String),
    Serialization,
}

impl std::fmt::Display for HookSpoolError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "hook spool I/O failed: {error}"),
            Self::InvalidEvent(error) => write!(formatter, "hook event rejected: {error}"),
            Self::Serialization => formatter.write_str("hook update serialization failed"),
        }
    }
}

impl std::error::Error for HookSpoolError {}

impl From<io::Error> for HookSpoolError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

#[derive(Debug, Clone)]
pub struct HookEventSpool {
    directory: PathBuf,
    thread_index_path: Option<PathBuf>,
}

#[derive(serde::Deserialize)]
struct HookSessionIdentity {
    session_id: String,
}

#[derive(serde::Deserialize)]
struct ThreadIndexRecord {
    id: String,
    thread_name: String,
}

#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HookDiagnosticOutcome {
    Invoked,
    Accepted,
}

#[derive(Debug, Clone)]
pub struct HookDiagnosticStore {
    path: PathBuf,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct HookDiagnosticRecord {
    protocol_version: u8,
    observed_at_ms: i64,
    outcome: HookDiagnosticOutcome,
}

impl HookDiagnosticStore {
    pub fn new(path: impl AsRef<Path>) -> Self {
        Self {
            path: path.as_ref().to_path_buf(),
        }
    }

    pub fn record(
        &self,
        observed_at_ms: i64,
        outcome: HookDiagnosticOutcome,
    ) -> Result<(), HookSpoolError> {
        if observed_at_ms < 0 {
            return Err(HookSpoolError::InvalidEvent(
                "invalid diagnostic timestamp".to_string(),
            ));
        }
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let serialized = serde_json::to_vec(&HookDiagnosticRecord {
            protocol_version: 1,
            observed_at_ms,
            outcome,
        })
        .map_err(|_| HookSpoolError::Serialization)?;
        fs::write(&self.path, serialized)?;
        Ok(())
    }
}

#[derive(Default)]
pub struct HookTaskRuntime {
    reducer: TaskReducer,
    sequence: u64,
    thread_index_signature: ThreadIndexSignature,
}

#[derive(Default, PartialEq, Eq)]
enum ThreadIndexSignature {
    #[default]
    Missing,
    Present {
        length: u64,
        modified: Option<std::time::SystemTime>,
    },
}

impl HookTaskRuntime {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn poll(
        &mut self,
        spool: &HookEventSpool,
        generated_at_ms: i64,
        chat_gpt_focused: bool,
    ) -> Result<Option<TaskSyncSnapshot>, HookSpoolError> {
        let mut updates = spool.drain_updates()?;
        if updates.is_empty() {
            let changed = spool.refresh_titles(&mut self.reducer, &mut self.thread_index_signature);
            if !changed {
                return Ok(None);
            }
            self.sequence = self.sequence.wrapping_add(1).max(1);
            return Ok(Some(self.reducer.sync_snapshot(
                self.sequence,
                generated_at_ms,
                ChatGptState::Running,
                chat_gpt_focused,
            )));
        }
        updates.sort_by_key(|update| update.updated_at_ms);
        let mut changed = false;
        for update in updates {
            changed |= self
                .reducer
                .ingest_update(update)
                .map_err(HookSpoolError::InvalidEvent)?
                .is_some();
        }
        changed |= spool.refresh_titles(&mut self.reducer, &mut self.thread_index_signature);
        if !changed {
            return Ok(None);
        }
        self.sequence = self.sequence.wrapping_add(1).max(1);
        Ok(Some(self.reducer.sync_snapshot(
            self.sequence,
            generated_at_ms,
            ChatGptState::Running,
            chat_gpt_focused,
        )))
    }
}

impl HookEventSpool {
    pub fn new(directory: impl AsRef<Path>) -> Self {
        Self {
            directory: directory.as_ref().to_path_buf(),
            thread_index_path: None,
        }
    }

    pub fn with_thread_index(
        directory: impl AsRef<Path>,
        thread_index_path: impl AsRef<Path>,
    ) -> Self {
        Self {
            directory: directory.as_ref().to_path_buf(),
            thread_index_path: Some(thread_index_path.as_ref().to_path_buf()),
        }
    }

    pub fn enqueue_json(&self, raw_event: &str, observed_at_ms: i64) -> Result<(), HookSpoolError> {
        let indexed_title = self
            .thread_index_path
            .as_deref()
            .and_then(|path| resolve_thread_title(path, raw_event));
        let title_override = self
            .thread_index_path
            .as_ref()
            .map(|_| indexed_title.as_deref().unwrap_or("任务"));
        let update = TaskReducer::new()
            .ingest_json_with_title(raw_event, observed_at_ms, title_override)
            .map_err(HookSpoolError::InvalidEvent)?;
        let Some(update) = update else {
            return Ok(());
        };
        let serialized = serde_json::to_vec(&update).map_err(|_| HookSpoolError::Serialization)?;
        fs::create_dir_all(&self.directory)?;
        let stem = event_stem();
        let temporary = self.directory.join(format!(".{stem}.tmp"));
        let destination = self.directory.join(format!("{stem}.json"));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(&serialized)?;
        file.sync_data()?;
        drop(file);
        fs::rename(&temporary, &destination)?;
        Ok(())
    }

    pub fn drain_updates(&self) -> Result<Vec<TaskUpdate>, HookSpoolError> {
        let entries = match fs::read_dir(&self.directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(error.into()),
        };
        let mut paths = entries
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| {
                path.extension()
                    .is_some_and(|extension| extension == "json")
            })
            .collect::<Vec<_>>();
        paths.sort();
        let mut updates = Vec::new();
        for path in paths {
            let parsed = fs::metadata(&path)
                .ok()
                .filter(|metadata| metadata.len() <= MAX_SPOOL_UPDATE_BYTES)
                .and_then(|_| fs::read(&path).ok())
                .and_then(|bytes| serde_json::from_slice::<TaskUpdate>(&bytes).ok());
            let _ = fs::remove_file(&path);
            if let Some(update) = parsed {
                updates.push(update);
            }
        }
        Ok(updates)
    }

    /// Refreshes generic task titles when ChatGPT updates its local index after the Hook event.
    /// This only reads the sanitized session index; it never reads prompt or reply content.
    fn refresh_titles(
        &self,
        reducer: &mut TaskReducer,
        previous_signature: &mut ThreadIndexSignature,
    ) -> bool {
        let Some(index_path) = self.thread_index_path.as_deref() else {
            return false;
        };
        let signature = match fs::metadata(index_path) {
            Ok(metadata) => ThreadIndexSignature::Present {
                length: metadata.len(),
                modified: metadata.modified().ok(),
            },
            Err(_) => ThreadIndexSignature::Missing,
        };
        if *previous_signature == signature {
            return false;
        }
        *previous_signature = signature;
        let length = match previous_signature {
            ThreadIndexSignature::Present { length, .. } => *length,
            ThreadIndexSignature::Missing => return false,
        };
        if length > MAX_THREAD_INDEX_BYTES {
            return false;
        }
        let Ok(contents) = fs::read_to_string(index_path) else {
            return false;
        };
        let mut titles = HashMap::new();
        for record in contents
            .lines()
            .filter_map(|line| serde_json::from_str::<ThreadIndexRecord>(line).ok())
        {
            let title = crate::sanitize_title(&record.thread_name);
            if title != "任务" {
                titles.insert(record.id, title);
            }
        }
        titles
            .into_iter()
            .fold(false, |changed, (conversation_id, title)| {
                reducer.refresh_indexed_title(&conversation_id, &title) || changed
            })
    }
}

fn resolve_thread_title(index_path: &Path, raw_event: &str) -> Option<String> {
    let identity: HookSessionIdentity = serde_json::from_str(raw_event).ok()?;
    let metadata = fs::metadata(index_path).ok()?;
    if metadata.len() > MAX_THREAD_INDEX_BYTES {
        return None;
    }
    let contents = fs::read_to_string(index_path).ok()?;
    contents
        .lines()
        .filter_map(|line| serde_json::from_str::<ThreadIndexRecord>(line).ok())
        .filter(|record| record.id == identity.session_id)
        .map(|record| crate::sanitize_title(&record.thread_name))
        .last()
}

fn event_stem() -> String {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let counter = EVENT_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{timestamp:032x}-{:08x}-{counter:016x}", std::process::id())
}

pub fn merge_hook_config(
    existing: &str,
    command: &str,
    command_windows: &str,
) -> Result<String, String> {
    if !command.contains(OWNER_MARKER) || !command_windows.contains(OWNER_MARKER) {
        return Err("product hook command is missing its owner marker".to_string());
    }
    let mut root = parse_hook_config(existing)?;
    remove_owned_handlers(&mut root)?;
    let hooks = root
        .as_object_mut()
        .expect("validated hook root")
        .entry("hooks")
        .or_insert_with(|| serde_json::json!({}));
    let hooks = hooks
        .as_object_mut()
        .ok_or_else(|| "hooks must be an object".to_string())?;
    for event in TASK_HOOK_EVENTS {
        let groups = hooks
            .entry(event)
            .or_insert_with(|| serde_json::json!([]))
            .as_array_mut()
            .ok_or_else(|| format!("hooks.{event} must be an array"))?;
        groups.push(serde_json::json!({
            "hooks": [{
                "type": "command",
                "command": command,
                "commandWindows": command_windows,
                "timeout": 3
            }]
        }));
    }
    serde_json::to_string_pretty(&root).map_err(|_| "hook config serialization failed".to_string())
}

pub fn remove_hook_config(existing: &str) -> Result<String, String> {
    let mut root = parse_hook_config(existing)?;
    remove_owned_handlers(&mut root)?;
    serde_json::to_string_pretty(&root).map_err(|_| "hook config serialization failed".to_string())
}

fn parse_hook_config(existing: &str) -> Result<serde_json::Value, String> {
    let value = if existing.trim().is_empty() {
        serde_json::json!({})
    } else {
        serde_json::from_str(existing).map_err(|_| "hooks.json is not valid JSON".to_string())?
    };
    if !value.is_object() {
        return Err("hooks.json root must be an object".to_string());
    }
    Ok(value)
}

fn remove_owned_handlers(root: &mut serde_json::Value) -> Result<(), String> {
    let root = root.as_object_mut().expect("validated hook root");
    let Some(hooks) = root.get_mut("hooks") else {
        root.insert("hooks".to_string(), serde_json::json!({}));
        return Ok(());
    };
    let hooks = hooks
        .as_object_mut()
        .ok_or_else(|| "hooks must be an object".to_string())?;
    let event_names = hooks.keys().cloned().collect::<Vec<_>>();
    for event_name in event_names {
        let groups = hooks
            .get_mut(&event_name)
            .and_then(serde_json::Value::as_array_mut)
            .ok_or_else(|| format!("hooks.{event_name} must be an array"))?;
        groups.retain_mut(|group| {
            let Some(handlers) = group
                .as_object_mut()
                .and_then(|object| object.get_mut("hooks"))
                .and_then(serde_json::Value::as_array_mut)
            else {
                return true;
            };
            handlers.retain(|handler| !owned_handler(handler));
            !handlers.is_empty()
        });
        if groups.is_empty() {
            hooks.remove(&event_name);
        }
    }
    Ok(())
}

fn owned_handler(handler: &serde_json::Value) -> bool {
    handler
        .as_object()
        .into_iter()
        .flat_map(|object| [object.get("command"), object.get("commandWindows")])
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .any(|command| command.contains(OWNER_MARKER))
}
