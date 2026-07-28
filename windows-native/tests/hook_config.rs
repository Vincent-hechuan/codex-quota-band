use codex_quota_windows_core::hook::{merge_hook_config, remove_hook_config};

#[test]
fn hook_merge_preserves_unrelated_user_configuration_and_is_idempotent() {
    let existing = r#"{
        "description": "my existing hooks",
        "customTopLevel": {"keep": true},
        "hooks": {
            "Stop": [{
                "matcher": "ignored by Stop",
                "hooks": [{"type": "command", "command": "existing-tool --stop"}]
            }],
            "SessionStart": [{
                "hooks": [{"type": "command", "command": "existing-tool --start"}]
            }]
        }
    }"#;
    let command =
        r#"\"C:\Program Files\CodexQuota\CodexQuota.exe\" --hook-event --owner codex-quota"#;
    let command_windows =
        r#"& 'C:\Program Files\CodexQuota\CodexQuota.exe' --hook-event --owner codex-quota"#;

    let once = merge_hook_config(existing, command, command_windows).expect("merge hook config");
    let twice = merge_hook_config(&once, command, command_windows).expect("repeat merge");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&once).unwrap(),
        serde_json::from_str::<serde_json::Value>(&twice).unwrap()
    );
    let json: serde_json::Value = serde_json::from_str(&once).expect("merged json");
    assert_eq!(json["description"], "my existing hooks");
    assert_eq!(json["customTopLevel"]["keep"], true);
    assert_eq!(
        json["hooks"]["SessionStart"][0]["hooks"][0]["command"],
        "existing-tool --start"
    );
    let serialized = json.to_string();
    assert!(serialized.contains("existing-tool --stop"));
    assert_eq!(serialized.matches("--owner codex-quota").count(), 8);
    for event in [
        "UserPromptSubmit",
        "PermissionRequest",
        "PreToolUse",
        "Stop",
    ] {
        assert!(json["hooks"][event].is_array(), "missing {event}");
        let owned_handler = json["hooks"][event]
            .as_array()
            .unwrap()
            .iter()
            .flat_map(|group| group["hooks"].as_array().unwrap())
            .find(|handler| {
                handler["commandWindows"]
                    .as_str()
                    .is_some_and(|value| value.contains("--owner codex-quota"))
            })
            .expect("missing product-owned handler");
        assert_eq!(owned_handler["commandWindows"], command_windows);
    }
}

#[test]
fn hook_remove_deletes_only_product_owned_handlers() {
    let command = r#"\"C:\CodexQuota.exe\" --hook-event --owner codex-quota"#;
    let command_windows = r#"& 'C:\CodexQuota.exe' --hook-event --owner codex-quota"#;
    let merged = merge_hook_config("{}", command, command_windows).expect("merge hook config");
    let removed = remove_hook_config(&merged).expect("remove hook config");
    let json: serde_json::Value = serde_json::from_str(&removed).expect("remaining json");
    assert!(!json.to_string().contains("--owner codex-quota"));
    assert_eq!(json["hooks"], serde_json::json!({}));
}
