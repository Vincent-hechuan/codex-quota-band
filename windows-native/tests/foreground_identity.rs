use codex_quota_windows_core::foreground::official_chatgpt_package;

#[test]
fn foreground_identity_accepts_only_official_chatgpt_package_names() {
    assert!(official_chatgpt_package(
        "OpenAI.Codex_26.715.10079.0_x64__2p2nqsd0c76g0"
    ));
    assert!(official_chatgpt_package(
        "OpenAI.ChatGPT_26.721.31836.0_x64__2p2nqsd0c76g0"
    ));
    assert!(!official_chatgpt_package(
        "ThirdParty.ChatGPT_1.0.0.0_x64__publisher"
    ));
    assert!(!official_chatgpt_package("ChatGPT.exe"));
    assert!(!official_chatgpt_package(
        "OpenAI.ChatGPTPreview_1.0_x64__publisher"
    ));
}
