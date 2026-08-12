#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![allow(unsafe_op_in_unsafe_fn)]

use chrono::{SecondsFormat, Utc};
use codex_quota_windows_core::foreground::chatgpt_is_foreground;
use codex_quota_windows_core::hook::{
    HookDiagnosticOutcome, HookDiagnosticStore, HookEventSpool, HookTaskRuntime, merge_hook_config,
    remove_hook_config,
};
use codex_quota_windows_core::host::{
    HostPaths, HostPublisher, PairingPresentation, WindowsHost, private_ipv4_addresses,
};
use codex_quota_windows_core::network::SyncPayload;
use codex_quota_windows_core::pairing_discovery::PairingDiscoveryAnnouncement;
use codex_quota_windows_core::quota::{
    QuotaCollector, UpstreamConfirmationErrorCode, load_cached_snapshot, save_cached_snapshot,
};
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus,
    ResetInventorySnapshot, ResetInventoryStatus, TaskSyncSnapshot, UpstreamFreshness,
    UpstreamFreshnessStatus,
};
use qrcode::{Color as QrColor, QrCode};
use std::collections::HashMap;
use std::io::Read;
use std::mem::size_of;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::ptr::{null, null_mut};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Registry::*;
use windows_sys::Win32::System::Threading::CreateMutexW;
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const APP_NAME: &str = "Codex额度";
const APP_ICON_RESOURCE_ID: usize = 101;
const HOST_PORT: u16 = 17_322;
const TRAY_ICON_ID: u32 = 1;
const TRAY_MESSAGE: u32 = WM_APP + 1;
const MENU_PAIR: u32 = 1001;
const MENU_REVOKE: u32 = 1002;
const MENU_STARTUP: u32 = 1003;
const MENU_EXIT: u32 = 1004;
const MENU_REPAIR_HOOK: u32 = 1005;
const MENU_REFRESH_STATUS: u32 = 1006;
const MENU_DIAGNOSTICS: u32 = 1007;
const DIAGNOSTICS_REFRESH_TIMER: usize = 1;
const MAX_RAW_HOOK_EVENT_BYTES: u64 = 256 * 1024;
const TRAY_ICON_ICO: &[u8] = include_bytes!("../../assets/tray-icon.ico");

const UI_BACKGROUND: u32 = rgb(234, 240, 243);
const UI_SURFACE: u32 = rgb(249, 251, 251);
const UI_INK: u32 = rgb(32, 38, 42);
const UI_MUTED: u32 = rgb(104, 116, 125);
const UI_PRIMARY: u32 = rgb(23, 111, 175);
const UI_WAITING: u32 = rgb(25, 128, 82);
const UI_CACHED: u32 = rgb(104, 118, 128);
const UI_BORDER: u32 = rgb(212, 222, 226);
const PAIRING_WIDTH: i32 = 460;
const PAIRING_HEIGHT: i32 = 620;
const PAIRING_TUTORIAL_WIDTH: i32 = 440;
const PAIRING_TUTORIAL_HEIGHT: i32 = 360;
const PAIRING_TUTORIAL_WINDOW_STYLE: WINDOW_STYLE = WS_POPUP | WS_BORDER;
const DIAGNOSTICS_WIDTH: i32 = 480;
const DIAGNOSTICS_HEIGHT: i32 = 340;
const DIAGNOSTICS_ROW_HEIGHT: i32 = 48;
const PAIRING_SURFACE_RADIUS: i32 = 32;
const PAIRING_TITLE_SIZE: i32 = 20;
const PAIRING_BODY_SIZE: i32 = 12;
const PAIRING_SUPPORT_SIZE: i32 = 10;
const PAIRING_GUIDANCE_SIZE: i32 = 13;
const PAIRING_CODE_SIZE: i32 = 22;
const PAIRING_QR_SIZE: i32 = 286;
const PAIRING_QR_TOP: i32 = 96;
const PAIRING_HOOK_GUIDANCE: &str = "使用手机端「Codex额度」App 扫码";
const PAIRING_APP_GUIDANCE: &str = "打开 App → 设置 → 连接电脑";
const PAIRING_NETWORK_GUIDANCE: &str = "确保手机和电脑连接同一局域网";
const PAIRING_TUTORIAL_BUTTON_LABEL: &str = "配对教学";
const PAIRING_REFRESH_BUTTON_LABEL: &str = "刷新配对码";
const PAIRING_TUTORIAL_STEPS: [&str; 4] = [
    "1. 在电脑托盘菜单中选择「安装/修复任务 Hook」",
    "2. 在 ChatGPT 中打开「设置 → 钩子」，并信任全部钩子",
    "3. 在手机端打开「Codex额度」App，进入「设置 → 连接电脑」",
    "4. 扫描二维码或输入 6 位配对码完成配对",
];
const HOOK_INSTALLED_GUIDANCE: &str =
    "任务 Hook 已安装或修复。\n请在 ChatGPT 中打开「设置 → 钩子」，并信任全部钩子。";

static APP: OnceLock<Arc<AppController>> = OnceLock::new();
static PAIRING_WINDOWS: OnceLock<Mutex<HashMap<isize, PairingWindowData>>> = OnceLock::new();

struct AppController {
    runtime: Arc<Runtime>,
    host: Mutex<Option<WindowsHost>>,
    quota_task: Mutex<Option<JoinHandle<()>>>,
    quota_state: Arc<RwLock<QuotaSnapshot>>,
    upstream_confirmation_in_progress: Arc<AtomicBool>,
    last_upstream_confirmation_error: Arc<Mutex<Option<UpstreamConfirmationErrorCode>>>,
}

impl AppController {
    fn active_connections(&self) -> usize {
        self.host
            .lock()
            .ok()
            .and_then(|host| host.as_ref().map(WindowsHost::active_sync_connections))
            .unwrap_or(0)
    }

    fn upstream_freshness(&self) -> UpstreamFreshness {
        self.quota_state
            .read()
            .map(|quota| quota.upstream_freshness.clone())
            .unwrap_or_default()
    }

    fn upstream_confirmation_in_progress(&self) -> bool {
        self.upstream_confirmation_in_progress
            .load(Ordering::Acquire)
    }

    fn last_upstream_confirmation_error(&self) -> Option<UpstreamConfirmationErrorCode> {
        self.last_upstream_confirmation_error
            .lock()
            .ok()
            .and_then(|error| *error)
    }

    fn confirm_upstream_now(&self) -> bool {
        if self
            .upstream_confirmation_in_progress
            .swap(true, Ordering::AcqRel)
        {
            return false;
        }
        let quota_state = self.quota_state.clone();
        let confirmation_in_progress = self.upstream_confirmation_in_progress.clone();
        let last_upstream_confirmation_error = self.last_upstream_confirmation_error.clone();
        self.runtime.spawn(async move {
            let refreshed = tokio::task::spawn_blocking(|| {
                QuotaCollector::discover().ok().map(|collector| {
                    collector.refresh_upstream_freshness_with_diagnostic(Utc::now())
                })
            })
            .await
            .ok()
            .flatten();
            if let Some((freshness, diagnostic)) = refreshed {
                if let Ok(mut quota) = quota_state.write() {
                    quota.upstream_freshness = freshness;
                }
                if let Ok(mut error) = last_upstream_confirmation_error.lock() {
                    *error = diagnostic;
                }
            }
            confirmation_in_progress.store(false, Ordering::Release);
        });
        true
    }

    fn pairing_presentation(&self) -> Result<PairingPresentation, String> {
        let addresses = private_ipv4_addresses().map_err(|error| error.to_string())?;
        let host = self
            .host
            .lock()
            .map_err(|_| "Windows 服务状态不可用".to_string())?;
        let host = host
            .as_ref()
            .ok_or_else(|| "Windows 服务已停止".to_string())?;
        self.runtime
            .block_on(host.begin_pairing(now_ms(), addresses))
            .map_err(|error| error.to_string())
    }

    fn revoke_phone(&self) -> Result<(), String> {
        let host = self
            .host
            .lock()
            .map_err(|_| "Windows 服务状态不可用".to_string())?;
        let host = host
            .as_ref()
            .ok_or_else(|| "Windows 服务已停止".to_string())?;
        self.runtime
            .block_on(host.revoke_phone())
            .map_err(|error| error.to_string())
    }

    fn shutdown(&self) {
        if let Some(task) = self.quota_task.lock().ok().and_then(|mut task| task.take()) {
            task.abort();
            let _ = self.runtime.block_on(task);
        }
        let host = self.host.lock().ok().and_then(|mut host| host.take());
        if let Some(host) = host {
            let _ = self.runtime.block_on(host.shutdown());
        }
    }
}

#[derive(Clone)]
struct PairingWindowData {
    modules: Vec<bool>,
    width: usize,
    pairing_code: String,
    security_code: String,
    manual_discovery_available: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PairingOpenAction {
    FocusExisting,
    CreateOffer,
}

fn pairing_open_action(existing_window: bool) -> PairingOpenAction {
    if existing_window {
        PairingOpenAction::FocusExisting
    } else {
        PairingOpenAction::CreateOffer
    }
}

#[derive(Clone, Copy)]
struct PairingWindowBottomLayout {
    app_guidance: RECT,
    manual_guidance: RECT,
    pairing_code: RECT,
    security_code: RECT,
    network_guidance: RECT,
    refresh_button: RECT,
    tutorial_button: RECT,
}

fn main() {
    let arguments = std::env::args().collect::<Vec<_>>();
    if arguments.iter().any(|argument| argument == "--smoke-test") {
        let result = unsafe {
            load_tray_icon().map(|icon| {
                DestroyIcon(icon);
            })
        };
        if let Err(error) = result {
            eprintln!("{error}");
            std::process::exit(1);
        }
        return;
    }
    if arguments.iter().any(|argument| argument == "--print-focus") {
        println!("{}", chatgpt_is_foreground());
        return;
    }
    if arguments.iter().any(|argument| argument == "--hook-event") {
        if let Err(error) = ingest_hook_event() {
            eprintln!("{error}");
            std::process::exit(1);
        }
        return;
    }
    if arguments
        .iter()
        .any(|argument| argument == "--install-hooks")
    {
        if let Err(error) = install_hook_config() {
            eprintln!("{error}");
            std::process::exit(1);
        }
        return;
    }
    if arguments
        .iter()
        .any(|argument| argument == "--remove-hooks")
    {
        if let Err(error) = uninstall_hook_config() {
            eprintln!("{error}");
            std::process::exit(1);
        }
        return;
    }
    let show_onboarding = arguments
        .iter()
        .any(|argument| argument == "--show-onboarding");
    if let Err(error) = run(show_onboarding) {
        show_message("Codex额度无法启动", &error, MB_ICONERROR);
    }
}

fn run(show_onboarding: bool) -> Result<(), String> {
    let mutex_name = wide("Local\\CodexQuotaWindowsNative040");
    // CreateMutexW signals an existing instance through LastError. Clear any
    // unrelated thread error first so a clean launch cannot be mistaken for a
    // duplicate instance.
    unsafe { SetLastError(0) };
    let instance_mutex = unsafe { CreateMutexW(null(), 0, mutex_name.as_ptr()) };
    if instance_mutex.is_null() {
        return Err("无法创建单实例锁".to_string());
    }
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        unsafe { CloseHandle(instance_mutex) };
        return Ok(());
    }
    let _ = ensure_default_startup_enabled();

    let data_directory = data_directory()?;
    let runtime = Arc::new(Runtime::new().map_err(|error| error.to_string())?);
    let collector = QuotaCollector::discover().ok();
    let snapshot_path = data_directory.join("last-snapshot-v1.json");
    let initial_quota = collector
        .as_ref()
        .and_then(|collector| collector.collect(Utc::now()).ok())
        .filter(|quota| !matches!(quota.link.codex, CodexLinkStatus::Unavailable))
        .or_else(|| load_cached_snapshot(&snapshot_path, Utc::now()))
        .unwrap_or_else(|| unavailable_payload().quota);
    let initial_payload = SyncPayload {
        quota: initial_quota.clone(),
        tasks: initial_tasks(now_ms()),
    };
    let quota_state = Arc::new(RwLock::new(initial_quota.clone()));
    let upstream_confirmation_in_progress = Arc::new(AtomicBool::new(false));
    let host = runtime
        .block_on(WindowsHost::start(
            HostPaths::in_data_directory(&data_directory),
            SocketAddr::from((Ipv4Addr::UNSPECIFIED, HOST_PORT)),
            initial_payload,
        ))
        .map_err(|error| error.to_string())?;
    let quota_task = start_quota_monitor(
        &runtime,
        host.publisher(),
        host.subscribe_refresh_requests(),
        collector,
        snapshot_path,
        Some(initial_quota),
        quota_state.clone(),
        HookEventSpool::with_thread_index(
            data_directory.join("hook-events-v1"),
            hooks_config_path()?.with_file_name("session_index.jsonl"),
        ),
    );
    APP.set(Arc::new(AppController {
        runtime,
        host: Mutex::new(Some(host)),
        quota_task: Mutex::new(Some(quota_task)),
        quota_state,
        upstream_confirmation_in_progress,
        last_upstream_confirmation_error: Arc::new(Mutex::new(None)),
    }))
    .map_err(|_| "应用状态初始化失败".to_string())?;

    unsafe {
        register_window_classes()?;
        let window = create_tray_window()?;
        let tray_icon = add_tray_icon(window)?;
        if show_onboarding {
            show_pairing_from_app();
        }
        if cfg!(debug_assertions) && std::env::args().any(|argument| argument == "--show-pairing") {
            show_pairing_from_app();
        }
        let mut message = MSG::default();
        while GetMessageW(&mut message, null_mut(), 0, 0) > 0 {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        remove_tray_icon(window);
        DestroyIcon(tray_icon);
    }
    if let Some(app) = APP.get() {
        app.shutdown();
    }
    unsafe { CloseHandle(instance_mutex) };
    Ok(())
}

unsafe fn register_window_classes() -> Result<(), String> {
    let instance = GetModuleHandleW(null());
    let app_icon = LoadIconW(instance, APP_ICON_RESOURCE_ID as *const u16);
    let tray_class_name = wide("CodexQuotaTrayWindow");
    let tray_class = WNDCLASSEXW {
        cbSize: size_of::<WNDCLASSEXW>() as u32,
        lpfnWndProc: Some(tray_window_proc),
        hInstance: instance,
        hCursor: LoadCursorW(null_mut(), IDC_ARROW),
        hIcon: app_icon,
        hIconSm: app_icon,
        lpszClassName: tray_class_name.as_ptr(),
        ..Default::default()
    };
    if RegisterClassExW(&tray_class) == 0 {
        return Err("无法注册托盘窗口".to_string());
    }

    let pairing_class_name = wide("CodexQuotaPairingWindow");
    let pairing_class = WNDCLASSEXW {
        cbSize: size_of::<WNDCLASSEXW>() as u32,
        lpfnWndProc: Some(pairing_window_proc),
        hInstance: instance,
        hCursor: LoadCursorW(null_mut(), IDC_ARROW),
        hIcon: app_icon,
        hIconSm: app_icon,
        lpszClassName: pairing_class_name.as_ptr(),
        ..Default::default()
    };
    if RegisterClassExW(&pairing_class) == 0 {
        return Err("无法注册配对窗口".to_string());
    }

    let tutorial_class_name = wide("CodexQuotaPairingTutorialWindow");
    let tutorial_class = WNDCLASSEXW {
        cbSize: size_of::<WNDCLASSEXW>() as u32,
        lpfnWndProc: Some(pairing_tutorial_window_proc),
        hInstance: instance,
        hCursor: LoadCursorW(null_mut(), IDC_ARROW),
        hIcon: app_icon,
        hIconSm: app_icon,
        lpszClassName: tutorial_class_name.as_ptr(),
        ..Default::default()
    };
    if RegisterClassExW(&tutorial_class) == 0 {
        return Err("无法注册配对教学窗口".to_string());
    }

    let diagnostics_class_name = wide("CodexQuotaDiagnosticsWindow");
    let diagnostics_class = WNDCLASSEXW {
        cbSize: size_of::<WNDCLASSEXW>() as u32,
        lpfnWndProc: Some(diagnostics_window_proc),
        hInstance: instance,
        hCursor: LoadCursorW(null_mut(), IDC_ARROW),
        hIcon: app_icon,
        hIconSm: app_icon,
        lpszClassName: diagnostics_class_name.as_ptr(),
        ..Default::default()
    };
    if RegisterClassExW(&diagnostics_class) == 0 {
        return Err("无法注册诊断窗口".to_string());
    }
    Ok(())
}

unsafe fn create_tray_window() -> Result<HWND, String> {
    let class_name = wide("CodexQuotaTrayWindow");
    let title = wide(APP_NAME);
    let window = CreateWindowExW(
        0,
        class_name.as_ptr(),
        title.as_ptr(),
        WS_OVERLAPPED,
        0,
        0,
        0,
        0,
        null_mut(),
        null_mut(),
        GetModuleHandleW(null()),
        null(),
    );
    if window.is_null() {
        Err("无法创建托盘窗口".to_string())
    } else {
        Ok(window)
    }
}

unsafe fn add_tray_icon(window: HWND) -> Result<HICON, String> {
    let mut data = tray_icon_data(window);
    data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    data.uCallbackMessage = TRAY_MESSAGE;
    let icon = load_tray_icon()?;
    data.hIcon = icon;
    copy_wide(&mut data.szTip, APP_NAME);
    if Shell_NotifyIconW(NIM_ADD, &data) == 0 {
        DestroyIcon(icon);
        Err("无法创建通知区域图标".to_string())
    } else {
        Ok(icon)
    }
}

unsafe fn load_tray_icon() -> Result<HICON, String> {
    if TRAY_ICON_ICO.len() < 22 || TRAY_ICON_ICO[0..6] != [0, 0, 1, 0, 1, 0] {
        return Err("托盘图标资源无效".to_string());
    }
    let image_size = u32::from_le_bytes(
        TRAY_ICON_ICO[14..18]
            .try_into()
            .map_err(|_| "托盘图标资源无效".to_string())?,
    );
    let image_offset = u32::from_le_bytes(
        TRAY_ICON_ICO[18..22]
            .try_into()
            .map_err(|_| "托盘图标资源无效".to_string())?,
    );
    let image_end = image_offset
        .checked_add(image_size)
        .ok_or_else(|| "托盘图标资源无效".to_string())? as usize;
    if image_offset < 22 || image_end > TRAY_ICON_ICO.len() {
        return Err("托盘图标资源无效".to_string());
    }
    let icon = CreateIconFromResourceEx(
        TRAY_ICON_ICO[image_offset as usize..image_end].as_ptr(),
        image_size,
        TRUE,
        0x0003_0000,
        32,
        32,
        LR_DEFAULTSIZE,
    );
    if icon.is_null() {
        Err("无法加载托盘图标".to_string())
    } else {
        Ok(icon)
    }
}

unsafe fn remove_tray_icon(window: HWND) {
    let data = tray_icon_data(window);
    Shell_NotifyIconW(NIM_DELETE, &data);
}

fn tray_icon_data(window: HWND) -> NOTIFYICONDATAW {
    NOTIFYICONDATAW {
        cbSize: size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: window,
        uID: TRAY_ICON_ID,
        ..Default::default()
    }
}

unsafe extern "system" fn tray_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        TRAY_MESSAGE => {
            let event = lparam as u32;
            if event == WM_RBUTTONUP || event == WM_CONTEXTMENU || event == WM_LBUTTONDBLCLK {
                show_tray_menu(window);
            }
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

unsafe fn show_tray_menu(window: HWND) {
    let menu = CreatePopupMenu();
    if menu.is_null() {
        return;
    }
    let connections = APP.get().map(|app| app.active_connections()).unwrap_or(0);
    let upstream = APP
        .get()
        .map(|app| app.upstream_freshness())
        .unwrap_or_default();
    let quota = APP
        .get()
        .and_then(|app| app.quota_state.read().ok().map(|quota| quota.clone()))
        .unwrap_or_else(|| unavailable_payload().quota);
    let quota_labels = tray_quota_labels(&quota);
    let confirmation_in_progress = APP
        .get()
        .is_some_and(|app| app.upstream_confirmation_in_progress());
    let (upstream_label, _) =
        upstream_usage_detail(&upstream, confirmation_in_progress, Utc::now());
    append_menu(menu, MF_STRING | MF_DISABLED, 0, "服务运行中");
    append_menu(
        menu,
        MF_STRING | MF_DISABLED,
        0,
        if connections > 0 {
            "手机 已连接"
        } else {
            "手机 未连接"
        },
    );
    append_menu(
        menu,
        MF_STRING | MF_DISABLED,
        0,
        &format!("额度 {upstream_label}"),
    );
    for label in quota_labels {
        append_menu(menu, MF_STRING | MF_DISABLED, 0, &label);
    }
    AppendMenuW(menu, MF_SEPARATOR, 0, null());
    append_menu(
        menu,
        if confirmation_in_progress {
            MF_STRING | MF_DISABLED
        } else {
            MF_STRING
        },
        MENU_REFRESH_STATUS as usize,
        if confirmation_in_progress {
            "正在刷新…"
        } else {
            "刷新当前状态"
        },
    );
    append_menu(menu, MF_STRING, MENU_PAIR as usize, "连接手机…");
    append_menu(menu, MF_STRING, MENU_DIAGNOSTICS as usize, "连接与诊断…");
    AppendMenuW(menu, MF_SEPARATOR, 0, null());
    append_menu(menu, MF_STRING, MENU_REVOKE as usize, "撤销手机配对");
    append_menu(
        menu,
        MF_STRING,
        MENU_REPAIR_HOOK as usize,
        "安装/修复任务 Hook…",
    );
    AppendMenuW(menu, MF_SEPARATOR, 0, null());
    let startup_flags = if startup_enabled() {
        MF_STRING | MF_CHECKED
    } else {
        MF_STRING
    };
    append_menu(
        menu,
        startup_flags,
        MENU_STARTUP as usize,
        "登录 Windows 时自动启动",
    );
    AppendMenuW(menu, MF_SEPARATOR, 0, null());
    append_menu(menu, MF_STRING, MENU_EXIT as usize, "退出");

    let mut cursor = POINT::default();
    GetCursorPos(&mut cursor);
    SetForegroundWindow(window);
    let command = TrackPopupMenu(
        menu,
        TPM_RIGHTBUTTON | TPM_RETURNCMD,
        cursor.x,
        cursor.y,
        0,
        window,
        null(),
    ) as u32;
    PostMessageW(window, WM_NULL, 0, 0);
    DestroyMenu(menu);
    match command {
        MENU_PAIR => show_pairing_from_app(),
        MENU_REVOKE => {
            let confirmed = MessageBoxW(
                window,
                wide("撤销后，Android 手机必须重新配对。是否继续？").as_ptr(),
                wide(APP_NAME).as_ptr(),
                MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2,
            );
            if confirmed == IDYES {
                if let Err(error) = APP
                    .get()
                    .ok_or_else(|| "应用尚未初始化".to_string())
                    .and_then(|app| app.revoke_phone())
                {
                    show_message("撤销失败", &error, MB_ICONERROR);
                }
            }
        }
        MENU_STARTUP => {
            let next = !startup_enabled();
            if let Err(error) = set_startup_enabled(next) {
                show_message("无法修改自动启动设置", &error, MB_ICONERROR);
            }
        }
        MENU_REFRESH_STATUS => {
            if let Some(app) = APP.get() {
                app.confirm_upstream_now();
            }
        }
        MENU_REPAIR_HOOK => match install_hook_config() {
            Ok(()) => show_message(APP_NAME, HOOK_INSTALLED_GUIDANCE, MB_ICONINFORMATION),
            Err(error) => show_message("任务 Hook 安装失败", &error, MB_ICONERROR),
        },
        MENU_DIAGNOSTICS => show_diagnostics_window(),
        MENU_EXIT => {
            DestroyWindow(window);
        }
        _ => {}
    }
}

unsafe fn show_pairing_from_app() {
    let class_name = wide("CodexQuotaPairingWindow");
    let existing = FindWindowW(class_name.as_ptr(), null());
    if pairing_open_action(!existing.is_null()) == PairingOpenAction::FocusExisting {
        ShowWindow(existing, SW_RESTORE);
        SetForegroundWindow(existing);
        return;
    }
    let result = APP
        .get()
        .ok_or_else(|| "应用尚未初始化".to_string())
        .and_then(|app| app.pairing_presentation());
    match result {
        Ok(presentation) => {
            if let Err(error) = show_pairing_window(presentation) {
                show_message("无法显示配对二维码", &error, MB_ICONWARNING);
            }
        }
        Err(error) => show_message("无法开始配对", &error, MB_ICONWARNING),
    }
}

unsafe fn append_menu(menu: HMENU, flags: MENU_ITEM_FLAGS, id: usize, label: &str) {
    let label = wide(label);
    AppendMenuW(menu, flags, id, label.as_ptr());
}

unsafe fn show_diagnostics_window() {
    let class_name = wide("CodexQuotaDiagnosticsWindow");
    let existing = FindWindowW(class_name.as_ptr(), null());
    if !existing.is_null() {
        ShowWindow(existing, SW_RESTORE);
        SetForegroundWindow(existing);
        return;
    }
    let title = wide("Codex额度 · 连接与诊断");
    let window = CreateWindowExW(
        WS_EX_APPWINDOW,
        class_name.as_ptr(),
        title.as_ptr(),
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        0,
        0,
        DIAGNOSTICS_WIDTH,
        DIAGNOSTICS_HEIGHT,
        null_mut(),
        null_mut(),
        GetModuleHandleW(null()),
        null(),
    );
    if window.is_null() {
        show_message("无法显示连接与诊断", "无法创建诊断窗口", MB_ICONWARNING);
        return;
    }
    center_auxiliary_window(window, null_mut());
    ShowWindow(window, SW_SHOWNORMAL);
    SetForegroundWindow(window);
    UpdateWindow(window);
    SetTimer(window, DIAGNOSTICS_REFRESH_TIMER, 250, None);
}

unsafe fn show_pairing_window(presentation: PairingPresentation) -> Result<(), String> {
    let data = pairing_window_data(presentation)?;
    let class_name = wide("CodexQuotaPairingWindow");
    let title = wide("Codex额度 · 连接手机");
    let window = CreateWindowExW(
        WS_EX_APPWINDOW,
        class_name.as_ptr(),
        title.as_ptr(),
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        0,
        0,
        PAIRING_WIDTH,
        PAIRING_HEIGHT,
        null_mut(),
        null_mut(),
        GetModuleHandleW(null()),
        null(),
    );
    if window.is_null() {
        return Err("无法创建配对窗口".to_string());
    }
    PAIRING_WINDOWS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| "配对窗口状态不可用".to_string())?
        .insert(window as isize, data);
    center_auxiliary_window(window, null_mut());
    ShowWindow(window, SW_SHOWNORMAL);
    SetForegroundWindow(window);
    UpdateWindow(window);
    Ok(())
}

fn pairing_window_data(presentation: PairingPresentation) -> Result<PairingWindowData, String> {
    let discovery =
        PairingDiscoveryAnnouncement::from_offer(&presentation.offer).map_err(str::to_string)?;
    let security_code = discovery.security_code();
    let manual_discovery_available = discovery.spawn_broadcast().is_ok();
    let code = QrCode::new(presentation.deep_link.as_bytes()).map_err(|error| error.to_string())?;
    let width = code.width();
    let modules = code
        .to_colors()
        .into_iter()
        .map(|color| color == QrColor::Dark)
        .collect();
    Ok(PairingWindowData {
        modules,
        width,
        pairing_code: presentation.offer.code.clone(),
        security_code,
        manual_discovery_available,
    })
}

unsafe fn refresh_pairing_window(window: HWND) {
    let result = APP
        .get()
        .ok_or_else(|| "应用尚未初始化".to_string())
        .and_then(|app| app.pairing_presentation())
        .and_then(pairing_window_data);
    match result {
        Ok(data) => {
            let updated = PAIRING_WINDOWS
                .get_or_init(|| Mutex::new(HashMap::new()))
                .lock()
                .map(|mut windows| windows.insert(window as isize, data))
                .is_ok();
            if updated {
                InvalidateRect(window, null(), 1);
                UpdateWindow(window);
            } else {
                show_message("无法刷新配对码", "配对窗口状态不可用", MB_ICONWARNING);
            }
        }
        Err(error) => show_message("无法刷新配对码", &error, MB_ICONWARNING),
    }
}

unsafe fn show_pairing_tutorial_window(owner: HWND) {
    let class_name = wide("CodexQuotaPairingTutorialWindow");
    let existing = FindWindowW(class_name.as_ptr(), null());
    if !existing.is_null() {
        ShowWindow(existing, SW_RESTORE);
        SetForegroundWindow(existing);
        return;
    }
    let title = wide("Codex额度 · 配对教学");
    let window = CreateWindowExW(
        WS_EX_TOOLWINDOW,
        class_name.as_ptr(),
        title.as_ptr(),
        PAIRING_TUTORIAL_WINDOW_STYLE,
        0,
        0,
        PAIRING_TUTORIAL_WIDTH,
        PAIRING_TUTORIAL_HEIGHT,
        owner,
        null_mut(),
        GetModuleHandleW(null()),
        null(),
    );
    if window.is_null() {
        show_message("无法显示配对教学", "无法创建配对教学窗口", MB_ICONWARNING);
        return;
    }
    center_auxiliary_window(window, owner);
    ShowWindow(window, SW_SHOWNORMAL);
    SetForegroundWindow(window);
    UpdateWindow(window);
}

fn centered_window_origin(work_area: RECT, width: i32, height: i32) -> POINT {
    POINT {
        x: work_area.left + ((work_area.right - work_area.left - width) / 2).max(0),
        y: work_area.top + ((work_area.bottom - work_area.top - height) / 2).max(0),
    }
}

fn clamp_window_origin(work_area: RECT, width: i32, height: i32, origin: POINT) -> POINT {
    let max_x = (work_area.right - width).max(work_area.left);
    let max_y = (work_area.bottom - height).max(work_area.top);
    POINT {
        x: origin.x.clamp(work_area.left, max_x),
        y: origin.y.clamp(work_area.top, max_y),
    }
}

unsafe fn center_auxiliary_window(window: HWND, owner: HWND) {
    let mut work_area = RECT::default();
    if SystemParametersInfoW(SPI_GETWORKAREA, 0, (&mut work_area as *mut RECT).cast(), 0) == 0 {
        work_area = RECT {
            left: 0,
            top: 0,
            right: GetSystemMetrics(SM_CXSCREEN),
            bottom: GetSystemMetrics(SM_CYSCREEN),
        };
    }
    let mut window_rect = RECT::default();
    GetWindowRect(window, &mut window_rect);
    let width = window_rect.right - window_rect.left;
    let height = window_rect.bottom - window_rect.top;
    let mut origin = centered_window_origin(work_area, width, height);
    if !owner.is_null() {
        let mut owner_rect = RECT::default();
        if GetWindowRect(owner, &mut owner_rect) != 0 {
            origin = POINT {
                x: (owner_rect.left + owner_rect.right - width) / 2,
                y: (owner_rect.top + owner_rect.bottom - height) / 2,
            };
        }
    }
    let origin = clamp_window_origin(work_area, width, height, origin);
    SetWindowPos(
        window,
        null_mut(),
        origin.x,
        origin.y,
        0,
        0,
        SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE,
    );
}

unsafe extern "system" fn pairing_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_PAINT => {
            paint_pairing_window(window);
            0
        }
        WM_LBUTTONUP => {
            let mut client = RECT::default();
            GetClientRect(window, &mut client);
            let x = (lparam as u32 & 0xffff) as i32;
            let y = ((lparam as u32 >> 16) & 0xffff) as i32;
            let refresh = pairing_refresh_button_rect(client);
            let button = pairing_tutorial_button_rect(client);
            if x >= refresh.left && x <= refresh.right && y >= refresh.top && y <= refresh.bottom {
                refresh_pairing_window(window);
            } else if x >= button.left && x <= button.right && y >= button.top && y <= button.bottom
            {
                show_pairing_tutorial_window(window);
            }
            0
        }
        WM_CLOSE => {
            DestroyWindow(window);
            0
        }
        WM_DESTROY => {
            if let Some(windows) = PAIRING_WINDOWS.get() {
                if let Ok(mut windows) = windows.lock() {
                    windows.remove(&(window as isize));
                }
            }
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

unsafe extern "system" fn pairing_tutorial_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_PAINT => {
            paint_pairing_tutorial_window(window);
            0
        }
        WM_LBUTTONUP => {
            let mut client = RECT::default();
            GetClientRect(window, &mut client);
            let x = (lparam as u32 & 0xffff) as i32;
            let y = ((lparam as u32 >> 16) & 0xffff) as i32;
            let acknowledge = pairing_tutorial_acknowledge_button_rect(client);
            let close = pairing_tutorial_header_close_button_rect(client);
            if (x >= acknowledge.left
                && x <= acknowledge.right
                && y >= acknowledge.top
                && y <= acknowledge.bottom)
                || (x >= close.left && x <= close.right && y >= close.top && y <= close.bottom)
            {
                DestroyWindow(window);
            }
            0
        }
        WM_NCHITTEST => {
            let mut point = POINT {
                x: (lparam as u32 & 0xffff) as i16 as i32,
                y: ((lparam as u32 >> 16) & 0xffff) as i16 as i32,
            };
            ScreenToClient(window, &mut point);
            let mut client = RECT::default();
            GetClientRect(window, &mut client);
            let close = pairing_tutorial_header_close_button_rect(client);
            if point.y >= 0
                && point.y < 56
                && !(point.x >= close.left
                    && point.x <= close.right
                    && point.y >= close.top
                    && point.y <= close.bottom)
            {
                HTCAPTION as LRESULT
            } else {
                HTCLIENT as LRESULT
            }
        }
        WM_CLOSE => {
            DestroyWindow(window);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

#[cfg(any())]
unsafe fn paint_pairing_window_legacy(window: HWND) {
    let data = PAIRING_WINDOWS
        .get()
        .and_then(|windows| windows.lock().ok())
        .and_then(|windows| windows.get(&(window as isize)).cloned());
    let Some(data) = data else {
        return;
    };
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(rgb(8, 12, 16));
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, rgb(244, 248, 250));
    draw_text(dc, "扫码配对", 0, 24, client.right, 62, 28, true);
    SetTextColor(dc, rgb(160, 176, 188));
    draw_text(
        dc,
        "使用手机系统相机扫描",
        0,
        66,
        client.right,
        92,
        16,
        true,
    );

    let qr_outer = RECT {
        left: 66,
        top: 104,
        right: 394,
        bottom: 432,
    };
    let white = CreateSolidBrush(rgb(255, 255, 255));
    FillRect(dc, &qr_outer, white);
    DeleteObject(white);
    let module_size = 300 / data.width.max(1) as i32;
    let qr_size = module_size * data.width as i32;
    let origin_x = (client.right - qr_size) / 2;
    let origin_y = 118 + (300 - qr_size) / 2;
    let black = CreateSolidBrush(rgb(7, 16, 20));
    for (index, dark) in data.modules.iter().copied().enumerate() {
        if !dark {
            continue;
        }
        let x = (index % data.width) as i32;
        let y = (index / data.width) as i32;
        let module = RECT {
            left: origin_x + x * module_size,
            top: origin_y + y * module_size,
            right: origin_x + (x + 1) * module_size,
            bottom: origin_y + (y + 1) * module_size,
        };
        FillRect(dc, &module, black);
    }
    DeleteObject(black);

    let guide_panel = RECT {
        left: 32,
        top: 456,
        right: client.right - 32,
        bottom: 604,
    };
    let guide_background = CreateSolidBrush(rgb(17, 28, 35));
    FillRect(dc, &guide_panel, guide_background);
    DeleteObject(guide_background);

    SetTextColor(dc, rgb(92, 224, 199));
    draw_text(
        dc,
        "手机 Codex额度 → 设置 → 扫码",
        guide_panel.left,
        470,
        guide_panel.right,
        500,
        16,
        true,
    );
    SetTextColor(dc, rgb(176, 190, 199));
    draw_text(
        dc,
        "同一局域网",
        guide_panel.left,
        518,
        guide_panel.right,
        542,
        14,
        true,
    );
    draw_text(
        dc,
        "首次使用：ChatGPT → 设置 → 钩子",
        guide_panel.left,
        544,
        guide_panel.right,
        568,
        14,
        true,
    );
    draw_text(
        dc,
        "信任全部钩子后重启 ChatGPT",
        guide_panel.left,
        570,
        guide_panel.right,
        594,
        13,
        true,
    );
    SetTextColor(dc, rgb(120, 140, 151));
    draw_text(
        dc,
        "二维码仅限本次配对，5 分钟内有效",
        20,
        614,
        client.right - 20,
        638,
        13,
        true,
    );
    EndPaint(window, &paint);
}

#[cfg(any())]
unsafe extern "system" fn onboarding_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_PAINT => {
            let mut paint = PAINTSTRUCT::default();
            let _ = BeginPaint(window, &mut paint);
            EndPaint(window, &paint);
            0
        }
        WM_CLOSE => {
            DestroyWindow(window);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

#[cfg(any())]
unsafe fn paint_onboarding_window(window: HWND) {
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, UI_PRIMARY);
    draw_text(
        dc,
        "本地 Windows 客户端",
        28,
        24,
        client.right - 28,
        46,
        12,
        false,
    );
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "在电脑上准备好同步",
        28,
        52,
        client.right - 28,
        88,
        27,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_multiline(
        dc,
        "Codex额度将在可信局域网内把额度摘要和经过裁剪的任务状态同步到你的 Android 手机。",
        28,
        94,
        client.right - 28,
        126,
        14,
    );

    let steps = RECT {
        left: 28,
        top: 148,
        right: client.right / 2 + 10,
        bottom: client.bottom - 88,
    };
    let status = RECT {
        left: client.right / 2 + 28,
        top: 148,
        right: client.right - 28,
        bottom: client.bottom - 88,
    };
    draw_surface(dc, steps, UI_SURFACE, UI_BORDER, 20);
    draw_surface(dc, status, UI_SURFACE, UI_BORDER, 20);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "开始使用",
        steps.left + 18,
        steps.top + 16,
        steps.right - 18,
        steps.top + 42,
        15,
        false,
    );
    draw_step(
        dc,
        steps,
        1,
        "安装任务 Hook",
        "在 ChatGPT → 设置 → 钩子中审阅并信任全部钩子。",
        56,
    );
    draw_step(
        dc,
        steps,
        2,
        "重启 ChatGPT",
        "让已信任的 Hook 开始提供任务状态摘要。",
        128,
    );
    draw_step(
        dc,
        steps,
        3,
        "扫描配对二维码",
        "在 Android 设置中使用系统相机连接此电脑。",
        200,
    );
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "服务状态",
        status.left + 18,
        status.top + 16,
        status.right - 18,
        status.top + 42,
        15,
        false,
    );
    draw_status_row(
        dc,
        status,
        "●",
        "服务已运行",
        "本地加密同步 · TLS 1.3",
        UI_WAITING,
        58,
    );
    draw_status_row(
        dc,
        status,
        "◌",
        "仅可信局域网",
        "不读取对话、命令或文件路径",
        UI_PRIMARY,
        132,
    );
    draw_status_row(
        dc,
        status,
        "✓",
        "手机可随时撤销",
        "托盘菜单中管理已配对设备",
        UI_MUTED,
        206,
    );
    EndPaint(window, &mut paint);
}

#[cfg(any())]
unsafe fn draw_step(dc: HDC, parent: RECT, number: i32, title: &str, detail: &str, top: i32) {
    let circle = CreateSolidBrush(rgb(227, 240, 248));
    let previous_brush = SelectObject(dc, circle);
    Ellipse(
        dc,
        parent.left + 18,
        parent.top + top,
        parent.left + 44,
        parent.top + top + 26,
    );
    SelectObject(dc, previous_brush);
    DeleteObject(circle);
    SetTextColor(dc, UI_PRIMARY);
    draw_text(
        dc,
        &number.to_string(),
        parent.left + 18,
        parent.top + top + 1,
        parent.left + 44,
        parent.top + top + 25,
        12,
        true,
    );
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        title,
        parent.left + 57,
        parent.top + top,
        parent.right - 18,
        parent.top + top + 23,
        14,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_multiline(
        dc,
        detail,
        parent.left + 57,
        parent.top + top + 25,
        parent.right - 18,
        parent.top + top + 56,
        12,
    );
}

#[cfg(any())]
unsafe fn draw_status_row(
    dc: HDC,
    parent: RECT,
    icon: &str,
    title: &str,
    detail: &str,
    color: u32,
    top: i32,
) {
    SetTextColor(dc, color);
    draw_text(
        dc,
        icon,
        parent.left + 18,
        parent.top + top,
        parent.left + 42,
        parent.top + top + 24,
        18,
        true,
    );
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        title,
        parent.left + 52,
        parent.top + top,
        parent.right - 18,
        parent.top + top + 22,
        14,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_multiline(
        dc,
        detail,
        parent.left + 52,
        parent.top + top + 24,
        parent.right - 18,
        parent.top + top + 52,
        11,
    );
}

unsafe extern "system" fn diagnostics_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_PAINT => {
            paint_diagnostics_window(window);
            0
        }
        WM_LBUTTONUP => {
            let mut client = RECT::default();
            GetClientRect(window, &mut client);
            let x = (lparam as u32 & 0xffff) as i32;
            let y = ((lparam as u32 >> 16) & 0xffff) as i32;
            let button = diagnostics_refresh_button_rect(client);
            if x >= button.left && x <= button.right && y >= button.top && y <= button.bottom {
                if let Some(app) = APP.get() {
                    app.confirm_upstream_now();
                    InvalidateRect(window, null(), 0);
                }
            }
            0
        }
        WM_TIMER => {
            InvalidateRect(window, null(), 0);
            0
        }
        WM_CLOSE => {
            KillTimer(window, DIAGNOSTICS_REFRESH_TIMER);
            DestroyWindow(window);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

#[cfg(any())]
unsafe fn paint_diagnostics_window_legacy(window: HWND) {
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, UI_PRIMARY);
    draw_text(dc, "本地诊断", 28, 24, client.right - 28, 46, 12, false);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "同步健康状态",
        28,
        52,
        client.right - 250,
        88,
        27,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_multiline(
        dc,
        "只显示版本、连接阶段、同步时间和错误代码；不包含对话或工具内容。",
        28,
        94,
        client.right - 28,
        126,
        14,
    );
    let left = RECT {
        left: 28,
        top: 148,
        right: client.right / 2 - 8,
        bottom: 340,
    };
    let right = RECT {
        left: client.right / 2 + 8,
        top: 148,
        right: client.right - 28,
        bottom: 340,
    };
    draw_surface(dc, left, UI_SURFACE, UI_BORDER, 18);
    draw_surface(dc, right, UI_SURFACE, UI_BORDER, 18);
    let connections = APP.get().map(|app| app.active_connections()).unwrap_or(0);
    let phone = if connections > 0 {
        "已连接"
    } else {
        "未连接"
    };
    let hook = if hooks_installed() {
        "已写入 · 等待 ChatGPT 信任"
    } else {
        "未安装"
    };
    draw_diag_item(
        dc,
        left,
        "Windows 服务",
        "运行中 · 局域网监听正常",
        UI_WAITING,
        22,
    );
    draw_diag_item(
        dc,
        left,
        "Android 手机",
        phone,
        if connections > 0 {
            UI_WAITING
        } else {
            UI_CACHED
        },
        87,
    );
    draw_diag_item(
        dc,
        left,
        "额度采集",
        "监控中 · 每 5 秒刷新",
        UI_PRIMARY,
        152,
    );
    draw_diag_item(
        dc,
        right,
        "ChatGPT Hook",
        hook,
        if hooks_installed() {
            UI_AUTHORIZATION
        } else {
            UI_CACHED
        },
        22,
    );
    draw_diag_item(
        dc,
        right,
        "任务摘要",
        "处理中 / 需要授权 / 等待查看",
        UI_RUNNING,
        87,
    );
    draw_diag_item(
        dc,
        right,
        "启动设置",
        if startup_enabled() {
            "登录 Windows 时自动启动"
        } else {
            "按需启动"
        },
        UI_MUTED,
        152,
    );
    EndPaint(window, &mut paint);
}

#[cfg(any())]
unsafe fn draw_diag_item(dc: HDC, parent: RECT, title: &str, detail: &str, color: u32, top: i32) {
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        title,
        parent.left + 16,
        parent.top + top,
        parent.right - 16,
        parent.top + top + 22,
        13,
        false,
    );
    SetTextColor(dc, color);
    draw_multiline(
        dc,
        detail,
        parent.left + 16,
        parent.top + top + 24,
        parent.right - 16,
        parent.top + top + 48,
        11,
    );
}

fn phone_connection_label(active_connections: usize) -> &'static str {
    if active_connections > 0 {
        "已连接"
    } else {
        "未连接"
    }
}

fn upstream_usage_label(
    freshness: &UpstreamFreshness,
    confirmation_in_progress: bool,
) -> (&'static str, u32) {
    if confirmation_in_progress {
        return ("同步中", UI_PRIMARY);
    }
    match freshness.usage.status {
        UpstreamFreshnessStatus::Current => ("已同步", UI_WAITING),
        UpstreamFreshnessStatus::Cached => ("缓存", UI_CACHED),
        UpstreamFreshnessStatus::Unavailable => ("待同步", UI_CACHED),
    }
}

fn tray_quota_labels(quota: &QuotaSnapshot) -> [String; 2] {
    [
        tray_quota_label(quota, "five_hour", 300, "5小时额度"),
        tray_quota_label(quota, "weekly", 10_080, "周额度"),
    ]
}

fn tray_quota_label(
    quota: &QuotaSnapshot,
    window_name: &str,
    window_minutes: u32,
    label: &str,
) -> String {
    let remaining = quota
        .windows
        .iter()
        .find(|window| window.name == window_name && window.window_minutes == window_minutes)
        .filter(|window| {
            window.status == codex_quota_windows_core::QuotaWindowStatus::Current
                && chrono::DateTime::parse_from_rfc3339(&window.resets_at)
                    .is_ok_and(|resets_at| resets_at.with_timezone(&Utc) > Utc::now())
        })
        .and_then(|window| window.remaining_percent);
    match remaining {
        Some(percent) => format!("{label} {percent}%"),
        None => format!("{label} --"),
    }
}

fn upstream_usage_detail(
    freshness: &UpstreamFreshness,
    confirmation_in_progress: bool,
    now: chrono::DateTime<Utc>,
) -> (String, u32) {
    let (label, color) = upstream_usage_label(freshness, confirmation_in_progress);
    if confirmation_in_progress || freshness.usage.status != UpstreamFreshnessStatus::Cached {
        return (label.to_string(), color);
    }
    let Some(last_success) = freshness
        .usage
        .last_success_at
        .as_deref()
        .and_then(|value| chrono::DateTime::parse_from_rfc3339(value).ok())
        .map(|value| value.with_timezone(&Utc))
    else {
        return (label.to_string(), color);
    };
    let elapsed_ms = now
        .signed_duration_since(last_success)
        .num_milliseconds()
        .max(0) as u64;
    (format!("{label} {}", elapsed_age_label(elapsed_ms)), color)
}

fn elapsed_age_label(elapsed_ms: u64) -> String {
    let elapsed_minutes = elapsed_ms / 60_000;
    match elapsed_minutes {
        0 => "刚刚".to_string(),
        1..=59 => format!("{elapsed_minutes}分"),
        60..=1_439 => format!("{}小时", elapsed_minutes / 60),
        1_440..=10_079 => format!("{}天", elapsed_minutes / (24 * 60)),
        10_080..=1_007_999 => format!("{}周", elapsed_minutes / (7 * 24 * 60)),
        _ => "99周+".to_string(),
    }
}

fn upstream_confirmation_guidance(error: Option<UpstreamConfirmationErrorCode>) -> &'static str {
    match error {
        None => "区分本地连接与额度上游确认状态",
        Some(UpstreamConfirmationErrorCode::AuthUnavailable) => {
            "最近确认失败 AUTH_UNAVAILABLE：请重新登录 Codex"
        }
        Some(UpstreamConfirmationErrorCode::AuthRejected) => {
            "最近确认失败 AUTH_REJECTED：请重新登录 Codex"
        }
        Some(UpstreamConfirmationErrorCode::Network) => "最近确认失败 NETWORK：检查网络或代理",
        Some(UpstreamConfirmationErrorCode::ResponseFormat) => {
            "最近确认失败 RESPONSE_FORMAT：请导出本地诊断"
        }
        Some(UpstreamConfirmationErrorCode::UpstreamHttp) => "最近确认失败 UPSTREAM_HTTP：稍后重试",
        Some(UpstreamConfirmationErrorCode::LocalWrite) => {
            "最近确认失败 LOCAL_WRITE：请导出本地诊断"
        }
    }
}

fn diagnostics_refresh_button_rect(client: RECT) -> RECT {
    RECT {
        left: client.right - 138,
        top: client.bottom - 62,
        right: client.right - 24,
        bottom: client.bottom - 28,
    }
}

fn diagnostics_panel_rect(client: RECT) -> RECT {
    RECT {
        left: 24,
        top: 94,
        right: client.right - 24,
        bottom: client.bottom - 70,
    }
}

unsafe fn paint_diagnostics_window(window: HWND) {
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, UI_INK);
    draw_text(dc, "连接诊断", 24, 24, client.right - 24, 54, 21, false);
    let diagnostic_guidance = APP
        .get()
        .and_then(|app| app.last_upstream_confirmation_error());
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        upstream_confirmation_guidance(diagnostic_guidance),
        24,
        58,
        client.right - 24,
        80,
        12,
        false,
    );
    let panel = diagnostics_panel_rect(client);
    draw_surface(dc, panel, UI_SURFACE, UI_BORDER, PAIRING_SURFACE_RADIUS);
    let connections = APP.get().map(|app| app.active_connections()).unwrap_or(0);
    let upstream = APP
        .get()
        .map(|app| app.upstream_freshness())
        .unwrap_or_default();
    let confirmation_in_progress = APP
        .get()
        .is_some_and(|app| app.upstream_confirmation_in_progress());
    let (upstream_label, upstream_color) =
        upstream_usage_detail(&upstream, confirmation_in_progress, Utc::now());
    draw_minimal_diagnostic_row(dc, panel, "Windows 服务", "运行中", UI_WAITING, 0);
    draw_minimal_diagnostic_row(
        dc,
        panel,
        "Android 手机",
        phone_connection_label(connections),
        if connections > 0 {
            UI_WAITING
        } else {
            UI_CACHED
        },
        1,
    );
    draw_minimal_diagnostic_row(
        dc,
        panel,
        "Codex 额度源",
        &upstream_label,
        upstream_color,
        2,
    );
    let button = diagnostics_refresh_button_rect(client);
    draw_surface(dc, button, UI_PRIMARY, UI_PRIMARY, 17);
    SetTextColor(dc, UI_SURFACE);
    draw_text(
        dc,
        if confirmation_in_progress {
            "确认中…"
        } else {
            "立即确认"
        },
        button.left,
        button.top - 1,
        button.right,
        button.bottom - 1,
        12,
        true,
    );
    EndPaint(window, &mut paint);
}

unsafe fn draw_minimal_diagnostic_row(
    dc: HDC,
    panel: RECT,
    label: &str,
    value: &str,
    color: u32,
    row: i32,
) {
    let text = diagnostics_row_text_rect(panel, row);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        label,
        panel.left + 16,
        text.top,
        panel.right - 120,
        text.bottom,
        13,
        false,
    );
    SetTextColor(dc, color);
    draw_text(
        dc,
        value,
        panel.right - 112,
        text.top,
        panel.right - 16,
        text.bottom,
        13,
        true,
    );
}

fn diagnostics_row_text_rect(panel: RECT, row: i32) -> RECT {
    let center = panel.top + 20 + row * DIAGNOSTICS_ROW_HEIGHT;
    RECT {
        left: panel.left,
        top: center - 11,
        right: panel.right,
        bottom: center + 11,
    }
}

fn pairing_tutorial_button_rect(client: RECT) -> RECT {
    pairing_window_bottom_layout(client, 0).tutorial_button
}

fn pairing_refresh_button_rect(client: RECT) -> RECT {
    pairing_window_bottom_layout(client, 0).refresh_button
}

fn pairing_window_bottom_layout(client: RECT, _qr_bottom: i32) -> PairingWindowBottomLayout {
    let button_top = client.bottom - 50;
    let button_bottom = client.bottom - 16;
    let button_width = 114;
    let button_gap = 12;
    let actions_left = (client.right - (button_width * 2 + button_gap)) / 2;
    let network_bottom = button_top - 8;
    let network_top = network_bottom - 15;
    let security_bottom = network_top - 4;
    let security_top = security_bottom - 15;
    let code_bottom = security_top - 4;
    let code_top = code_bottom - 28;
    let manual_bottom = code_top - 4;
    let manual_top = manual_bottom - 18;
    let app_bottom = manual_top - 4;
    let app_top = app_bottom - 18;
    PairingWindowBottomLayout {
        app_guidance: RECT {
            left: 0,
            top: app_top,
            right: client.right,
            bottom: app_bottom,
        },
        manual_guidance: RECT {
            left: 0,
            top: manual_top,
            right: client.right,
            bottom: manual_bottom,
        },
        pairing_code: RECT {
            left: 0,
            top: code_top,
            right: client.right,
            bottom: code_bottom,
        },
        security_code: RECT {
            left: 0,
            top: security_top,
            right: client.right,
            bottom: security_bottom,
        },
        network_guidance: RECT {
            left: 18,
            top: network_top,
            right: client.right - 18,
            bottom: network_bottom,
        },
        refresh_button: RECT {
            left: actions_left,
            top: button_top,
            right: actions_left + button_width,
            bottom: button_bottom,
        },
        tutorial_button: RECT {
            left: actions_left + button_width + button_gap,
            top: button_top,
            right: actions_left + button_width * 2 + button_gap,
            bottom: button_bottom,
        },
    }
}

fn pairing_tutorial_acknowledge_button_rect(client: RECT) -> RECT {
    RECT {
        left: client.right - 124,
        top: client.bottom - 52,
        right: client.right - 24,
        bottom: client.bottom - 18,
    }
}

fn pairing_tutorial_header_close_button_rect(client: RECT) -> RECT {
    RECT {
        left: client.right - 48,
        top: 10,
        right: client.right - 16,
        bottom: 42,
    }
}

unsafe fn paint_pairing_window(window: HWND) {
    let data = PAIRING_WINDOWS
        .get()
        .and_then(|windows| windows.lock().ok())
        .and_then(|windows| windows.get(&(window as isize)).cloned());
    let Some(data) = data else {
        return;
    };
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "连接手机",
        0,
        27,
        client.right,
        55,
        PAIRING_TITLE_SIZE,
        true,
    );
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        PAIRING_HOOK_GUIDANCE,
        0,
        61,
        client.right,
        79,
        PAIRING_BODY_SIZE,
        true,
    );
    let qr_box_size = PAIRING_QR_SIZE
        .min(client.right - 96)
        .min(client.bottom - 235);
    let qr_left = (client.right - qr_box_size) / 2;
    let qr_top = PAIRING_QR_TOP;
    let outer = RECT {
        left: qr_left - 12,
        top: qr_top - 12,
        right: qr_left + qr_box_size + 12,
        bottom: qr_top + qr_box_size + 12,
    };
    draw_surface(dc, outer, UI_SURFACE, UI_BORDER, PAIRING_SURFACE_RADIUS);
    let module_size = qr_box_size / data.width.max(1) as i32;
    let qr_size = module_size * data.width as i32;
    let origin_x = qr_left + (qr_box_size - qr_size) / 2;
    let origin_y = qr_top + (qr_box_size - qr_size) / 2;
    let black = CreateSolidBrush(UI_INK);
    for (index, dark) in data.modules.iter().copied().enumerate() {
        if dark {
            let x = (index % data.width) as i32;
            let y = (index / data.width) as i32;
            let module = RECT {
                left: origin_x + x * module_size,
                top: origin_y + y * module_size,
                right: origin_x + (x + 1) * module_size,
                bottom: origin_y + (y + 1) * module_size,
            };
            FillRect(dc, &module, black);
        }
    }
    DeleteObject(black);
    let bottom_layout = pairing_window_bottom_layout(client, outer.bottom);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        PAIRING_APP_GUIDANCE,
        bottom_layout.app_guidance.left,
        bottom_layout.app_guidance.top,
        bottom_layout.app_guidance.right,
        bottom_layout.app_guidance.bottom,
        PAIRING_GUIDANCE_SIZE,
        true,
    );
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        PAIRING_NETWORK_GUIDANCE,
        bottom_layout.network_guidance.left,
        bottom_layout.network_guidance.top,
        bottom_layout.network_guidance.right,
        bottom_layout.network_guidance.bottom,
        PAIRING_SUPPORT_SIZE,
        true,
    );
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        if data.manual_discovery_available {
            "无法扫码？输入下方 6 位配对码"
        } else {
            "局域网发现不可用，请使用二维码"
        },
        bottom_layout.manual_guidance.left,
        bottom_layout.manual_guidance.top,
        bottom_layout.manual_guidance.right,
        bottom_layout.manual_guidance.bottom,
        PAIRING_BODY_SIZE,
        true,
    );
    SetTextColor(dc, UI_PRIMARY);
    draw_text(
        dc,
        if data.manual_discovery_available {
            &data.pairing_code
        } else {
            "--"
        },
        bottom_layout.pairing_code.left,
        bottom_layout.pairing_code.top,
        bottom_layout.pairing_code.right,
        bottom_layout.pairing_code.bottom,
        PAIRING_CODE_SIZE,
        true,
    );
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        &if data.manual_discovery_available {
            format!("安全校验码 {} · 5 分钟内有效", data.security_code)
        } else {
            "关闭窗口后重试，或直接扫码".to_string()
        },
        bottom_layout.security_code.left,
        bottom_layout.security_code.top,
        bottom_layout.security_code.right,
        bottom_layout.security_code.bottom,
        PAIRING_SUPPORT_SIZE,
        true,
    );
    let refresh_button = bottom_layout.refresh_button;
    draw_anti_aliased_surface(
        dc,
        refresh_button,
        UI_PRIMARY,
        UI_PRIMARY,
        UI_BACKGROUND,
        refresh_button.bottom - refresh_button.top,
    );
    SetTextColor(dc, UI_SURFACE);
    draw_text(
        dc,
        PAIRING_REFRESH_BUTTON_LABEL,
        refresh_button.left,
        refresh_button.top + 6,
        refresh_button.right,
        refresh_button.bottom - 4,
        PAIRING_BODY_SIZE,
        true,
    );
    let tutorial_button = pairing_tutorial_button_rect(client);
    draw_anti_aliased_surface(
        dc,
        tutorial_button,
        UI_SURFACE,
        UI_BORDER,
        UI_BACKGROUND,
        tutorial_button.bottom - tutorial_button.top,
    );
    SetTextColor(dc, UI_PRIMARY);
    draw_text(
        dc,
        PAIRING_TUTORIAL_BUTTON_LABEL,
        tutorial_button.left,
        tutorial_button.top + 7,
        tutorial_button.right,
        tutorial_button.bottom - 5,
        PAIRING_BODY_SIZE,
        true,
    );
    EndPaint(window, &paint);
}

unsafe fn paint_pairing_tutorial_window(window: HWND) {
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);

    SetTextColor(dc, UI_INK);
    draw_text(dc, "配对教学", 24, 14, client.right - 64, 46, 21, false);
    let close = pairing_tutorial_header_close_button_rect(client);
    draw_surface(dc, close, UI_SURFACE, UI_BORDER, 16);
    draw_close_icon(dc, close);

    let panel = RECT {
        left: 24,
        top: 62,
        right: client.right - 24,
        bottom: client.bottom - 60,
    };
    draw_surface(dc, panel, UI_SURFACE, UI_BORDER, 24);
    SetTextColor(dc, UI_INK);
    let row_tops = [82, 124, 172, 226];
    let row_bottoms = [116, 164, 218, 260];
    for ((step, top), bottom) in PAIRING_TUTORIAL_STEPS.iter().zip(row_tops).zip(row_bottoms) {
        draw_multiline(dc, step, panel.left + 18, top, panel.right - 18, bottom, 12);
    }

    let button = pairing_tutorial_acknowledge_button_rect(client);
    draw_surface(dc, button, UI_PRIMARY, UI_PRIMARY, 17);
    SetTextColor(dc, UI_SURFACE);
    draw_text(
        dc,
        "我知道了",
        button.left,
        button.top - 1,
        button.right,
        button.bottom - 1,
        12,
        true,
    );
    EndPaint(window, &paint);
}

unsafe fn draw_close_icon(dc: HDC, rectangle: RECT) {
    let pen = CreatePen(PS_SOLID, 1, UI_MUTED);
    let previous = SelectObject(dc, pen);
    let inset = 11;
    MoveToEx(
        dc,
        rectangle.left + inset,
        rectangle.top + inset,
        null_mut(),
    );
    LineTo(dc, rectangle.right - inset, rectangle.bottom - inset);
    MoveToEx(
        dc,
        rectangle.right - inset,
        rectangle.top + inset,
        null_mut(),
    );
    LineTo(dc, rectangle.left + inset, rectangle.bottom - inset);
    SelectObject(dc, previous);
    DeleteObject(pen);
}

#[cfg(any())]
unsafe fn paint_pairing_window_compact(window: HWND) {
    let data = PAIRING_WINDOWS
        .get()
        .and_then(|windows| windows.lock().ok())
        .and_then(|windows| windows.get(&(window as isize)).cloned());
    let Some(data) = data else {
        return;
    };
    let mut paint = PAINTSTRUCT::default();
    let dc = BeginPaint(window, &mut paint);
    let mut client = RECT::default();
    GetClientRect(window, &mut client);
    let background = CreateSolidBrush(UI_BACKGROUND);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, UI_PRIMARY);
    draw_text(
        dc,
        "连接 Android 手机",
        30,
        28,
        client.right - 320,
        61,
        26,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        "使用手机系统相机扫描二维码",
        30,
        67,
        client.right - 320,
        92,
        14,
        false,
    );
    let guide = RECT {
        left: 30,
        top: 122,
        right: client.right - 330,
        bottom: client.bottom - 70,
    };
    draw_surface(dc, guide, UI_SURFACE, UI_BORDER, 18);
    SetTextColor(dc, UI_INK);
    draw_text(
        dc,
        "请在 Android 中打开",
        guide.left + 18,
        guide.top + 20,
        guide.right - 18,
        guide.top + 46,
        15,
        false,
    );
    SetTextColor(dc, UI_MUTED);
    draw_multiline(
        dc,
        "Codex额度 → 设置 → 扫码连接电脑\n\n二维码仅用于本次配对，5 分钟内有效。\n手机和电脑需要处于同一可信局域网。",
        guide.left + 18,
        guide.top + 58,
        guide.right - 18,
        guide.bottom - 18,
        13,
    );
    let qr_box_size = (client.bottom - 160).min(client.right / 2);
    let qr_left = client.right - qr_box_size - 42;
    let qr_top = 106;
    let qr_outer = RECT {
        left: qr_left - 14,
        top: qr_top - 14,
        right: qr_left + qr_box_size + 14,
        bottom: qr_top + qr_box_size + 14,
    };
    let white = CreateSolidBrush(UI_SURFACE);
    draw_round_rect(dc, qr_outer, white, UI_BORDER, 18);
    DeleteObject(white);
    let module_size = qr_box_size / data.width.max(1) as i32;
    let qr_size = module_size * data.width as i32;
    let origin_x = qr_left + (qr_box_size - qr_size) / 2;
    let origin_y = qr_top + (qr_box_size - qr_size) / 2;
    let black = CreateSolidBrush(UI_INK);
    for (index, dark) in data.modules.iter().copied().enumerate() {
        if !dark {
            continue;
        }
        let x = (index % data.width) as i32;
        let y = (index / data.width) as i32;
        let module = RECT {
            left: origin_x + x * module_size,
            top: origin_y + y * module_size,
            right: origin_x + (x + 1) * module_size,
            bottom: origin_y + (y + 1) * module_size,
        };
        FillRect(dc, &module, black);
    }
    DeleteObject(black);
    SetTextColor(dc, UI_MUTED);
    draw_text(
        dc,
        "等待手机扫描 · 5 分钟内有效",
        qr_outer.left,
        qr_outer.bottom + 16,
        client.right - 24,
        qr_outer.bottom + 38,
        12,
        true,
    );
    EndPaint(window, &mut paint);
}

unsafe fn draw_surface(dc: HDC, rectangle: RECT, fill: u32, border: u32, radius: i32) {
    let brush = CreateSolidBrush(fill);
    draw_round_rect(dc, rectangle, brush, border, radius);
    DeleteObject(brush);
}

unsafe fn draw_anti_aliased_surface(
    dc: HDC,
    rectangle: RECT,
    fill: u32,
    border: u32,
    background: u32,
    radius: i32,
) {
    const SCALE: i32 = 4;
    let width = rectangle.right - rectangle.left;
    let height = rectangle.bottom - rectangle.top;
    if width <= 0 || height <= 0 {
        return;
    }
    let memory_dc = CreateCompatibleDC(dc);
    let bitmap = CreateCompatibleBitmap(dc, width * SCALE, height * SCALE);
    if memory_dc.is_null() || bitmap.is_null() {
        if !memory_dc.is_null() {
            DeleteDC(memory_dc);
        }
        if !bitmap.is_null() {
            DeleteObject(bitmap);
        }
        draw_surface(dc, rectangle, fill, border, radius);
        return;
    }
    let previous_bitmap = SelectObject(memory_dc, bitmap);
    let scaled = RECT {
        left: 0,
        top: 0,
        right: width * SCALE,
        bottom: height * SCALE,
    };
    let background_brush = CreateSolidBrush(background);
    FillRect(memory_dc, &scaled, background_brush);
    DeleteObject(background_brush);
    let fill_brush = CreateSolidBrush(fill);
    draw_round_rect(memory_dc, scaled, fill_brush, border, radius * SCALE);
    DeleteObject(fill_brush);
    SetStretchBltMode(dc, HALFTONE);
    SetBrushOrgEx(dc, rectangle.left, rectangle.top, null_mut());
    StretchBlt(
        dc,
        rectangle.left,
        rectangle.top,
        width,
        height,
        memory_dc,
        0,
        0,
        width * SCALE,
        height * SCALE,
        SRCCOPY,
    );
    SelectObject(memory_dc, previous_bitmap);
    DeleteObject(bitmap);
    DeleteDC(memory_dc);
}

unsafe fn draw_round_rect(dc: HDC, rectangle: RECT, brush: HBRUSH, border: u32, radius: i32) {
    let pen = CreatePen(PS_SOLID, 1, border);
    let previous_pen = SelectObject(dc, pen);
    let previous_brush = SelectObject(dc, brush);
    RoundRect(
        dc,
        rectangle.left,
        rectangle.top,
        rectangle.right,
        rectangle.bottom,
        radius,
        radius,
    );
    SelectObject(dc, previous_brush);
    SelectObject(dc, previous_pen);
    DeleteObject(pen);
}

unsafe fn draw_multiline(
    dc: HDC,
    text: &str,
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
    size: i32,
) {
    let face = wide("Microsoft YaHei UI");
    let font = CreateFontW(
        -size,
        0,
        0,
        0,
        FW_NORMAL as i32,
        0,
        0,
        0,
        DEFAULT_CHARSET as u32,
        OUT_DEFAULT_PRECIS as u32,
        CLIP_DEFAULT_PRECIS as u32,
        CLEARTYPE_QUALITY as u32,
        DEFAULT_PITCH as u32,
        face.as_ptr(),
    );
    let previous = SelectObject(dc, font);
    let mut rectangle = RECT {
        left,
        top,
        right,
        bottom,
    };
    let text = wide(text);
    DrawTextW(
        dc,
        text.as_ptr(),
        -1,
        &mut rectangle,
        DT_LEFT | DT_TOP | DT_WORDBREAK | DT_END_ELLIPSIS,
    );
    SelectObject(dc, previous);
    DeleteObject(font);
}

unsafe fn draw_text(
    dc: HDC,
    text: &str,
    left: i32,
    top: i32,
    right: i32,
    bottom: i32,
    size: i32,
    centered: bool,
) {
    let face = wide("Microsoft YaHei UI");
    let font = CreateFontW(
        -size,
        0,
        0,
        0,
        FW_NORMAL as i32,
        0,
        0,
        0,
        DEFAULT_CHARSET as u32,
        OUT_DEFAULT_PRECIS as u32,
        CLIP_DEFAULT_PRECIS as u32,
        CLEARTYPE_QUALITY as u32,
        DEFAULT_PITCH as u32,
        face.as_ptr(),
    );
    let previous = SelectObject(dc, font);
    let mut rectangle = RECT {
        left,
        top,
        right,
        bottom,
    };
    let text = wide(text);
    let alignment = if centered { DT_CENTER } else { DT_LEFT };
    DrawTextW(
        dc,
        text.as_ptr(),
        -1,
        &mut rectangle,
        alignment | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS,
    );
    SelectObject(dc, previous);
    DeleteObject(font);
}

fn startup_enabled() -> bool {
    unsafe {
        let mut key = HKEY::default();
        let path = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        if RegOpenKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut key,
        ) != ERROR_SUCCESS
        {
            return false;
        }
        let name = wide("CodexQuota");
        let result = RegQueryValueExW(
            key,
            name.as_ptr(),
            null(),
            null_mut(),
            null_mut(),
            null_mut(),
        );
        RegCloseKey(key);
        result == ERROR_SUCCESS
    }
}

fn set_startup_enabled(enabled: bool) -> Result<(), String> {
    unsafe {
        let path = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        let mut key = HKEY::default();
        if RegCreateKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            null_mut(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            null(),
            &mut key,
            null_mut(),
        ) != ERROR_SUCCESS
        {
            return Err("无法打开当前用户启动项".to_string());
        }
        let name = wide("CodexQuota");
        let result = if enabled {
            let executable = std::env::current_exe().map_err(|error| error.to_string())?;
            let command = wide(&format!("\"{}\"", executable.display()));
            RegSetValueExW(
                key,
                name.as_ptr(),
                0,
                REG_SZ,
                command.as_ptr().cast(),
                (command.len() * size_of::<u16>()) as u32,
            )
        } else {
            RegDeleteValueW(key, name.as_ptr())
        };
        RegCloseKey(key);
        if result == ERROR_SUCCESS || (!enabled && result == ERROR_FILE_NOT_FOUND) {
            mark_startup_preference_configured()
        } else {
            Err(format!("Windows 注册表返回错误 {result}"))
        }
    }
}

fn startup_preference_configured() -> bool {
    unsafe {
        let mut key = HKEY::default();
        let path = wide("Software\\CodexQuota");
        if RegOpenKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut key,
        ) != ERROR_SUCCESS
        {
            return false;
        }
        let name = wide("StartupPreferenceConfigured");
        let result = RegQueryValueExW(
            key,
            name.as_ptr(),
            null(),
            null_mut(),
            null_mut(),
            null_mut(),
        );
        RegCloseKey(key);
        result == ERROR_SUCCESS
    }
}

fn mark_startup_preference_configured() -> Result<(), String> {
    unsafe {
        let path = wide("Software\\CodexQuota");
        let mut key = HKEY::default();
        if RegCreateKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            null_mut(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            null(),
            &mut key,
            null_mut(),
        ) != ERROR_SUCCESS
        {
            return Err("无法保存自动启动偏好".to_string());
        }
        let name = wide("StartupPreferenceConfigured");
        let configured = 1_u32;
        let result = RegSetValueExW(
            key,
            name.as_ptr(),
            0,
            REG_DWORD,
            (&configured as *const u32).cast(),
            size_of::<u32>() as u32,
        );
        RegCloseKey(key);
        if result == ERROR_SUCCESS {
            Ok(())
        } else {
            Err(format!("Windows 注册表返回错误 {result}"))
        }
    }
}

fn ensure_default_startup_enabled() -> Result<(), String> {
    if startup_preference_configured() {
        Ok(())
    } else {
        set_startup_enabled(true)
    }
}

fn unavailable_payload() -> SyncPayload {
    SyncPayload {
        quota: QuotaSnapshot {
            protocol_version: 1,
            generated_at: Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true),
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
        tasks: unavailable_tasks(now_ms()),
    }
}

fn unavailable_tasks(generated_at_ms: i64) -> TaskSyncSnapshot {
    TaskSyncSnapshot {
        protocol_version: 1,
        sequence: 0,
        generated_at_ms,
        chat_gpt_state: ChatGptState::HookUnavailable,
        chat_gpt_focused: false,
        tasks: vec![],
    }
}

fn initial_tasks(generated_at_ms: i64) -> TaskSyncSnapshot {
    if hooks_installed() {
        TaskSyncSnapshot {
            protocol_version: 1,
            sequence: 0,
            generated_at_ms,
            chat_gpt_state: ChatGptState::Running,
            chat_gpt_focused: chatgpt_is_foreground(),
            tasks: Vec::new(),
        }
    } else {
        unavailable_tasks(generated_at_ms)
    }
}

fn hooks_installed() -> bool {
    hooks_config_path()
        .ok()
        .and_then(|path| std::fs::read_to_string(path).ok())
        .is_some_and(|contents| contents.contains("--owner codex-quota"))
}

fn ingest_hook_event() -> Result<(), String> {
    let data_directory = data_directory()?;
    let diagnostic = HookDiagnosticStore::new(data_directory.join("hook-status-v1.json"));
    let observed_at_ms = now_ms();
    diagnostic
        .record(observed_at_ms, HookDiagnosticOutcome::Invoked)
        .map_err(|error| error.to_string())?;
    let mut raw_event = Vec::new();
    std::io::stdin()
        .take(MAX_RAW_HOOK_EVENT_BYTES + 1)
        .read_to_end(&mut raw_event)
        .map_err(|error| format!("无法读取 Hook 事件：{error}"))?;
    if raw_event.len() as u64 > MAX_RAW_HOOK_EVENT_BYTES {
        return Err("Hook 事件过大".to_string());
    }
    let raw_event = String::from_utf8(raw_event).map_err(|_| "Hook 事件不是 UTF-8".to_string())?;
    let thread_index_path = hooks_config_path()?.with_file_name("session_index.jsonl");
    HookEventSpool::with_thread_index(data_directory.join("hook-events-v1"), thread_index_path)
        .enqueue_json(&raw_event, observed_at_ms)
        .map_err(|error| error.to_string())?;
    diagnostic
        .record(observed_at_ms, HookDiagnosticOutcome::Accepted)
        .map_err(|error| error.to_string())
}

fn install_hook_config() -> Result<(), String> {
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let executable_path = executable.to_string_lossy();
    let command = format!("\"{}\" --hook-event --owner codex-quota", executable_path);
    let powershell_path = executable_path.replace('\'', "''");
    let command_windows = format!("& '{powershell_path}' --hook-event --owner codex-quota");
    let path = hooks_config_path()?;
    let existing = match std::fs::read_to_string(&path) {
        Ok(existing) => existing,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => "{}".to_string(),
        Err(error) => return Err(format!("无法读取 {}：{error}", path.display())),
    };
    let merged = merge_hook_config(&existing, &command, &command_windows)?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    std::fs::write(&path, format!("{merged}\n"))
        .map_err(|error| format!("无法写入 {}：{error}", path.display()))
}

fn uninstall_hook_config() -> Result<(), String> {
    let path = hooks_config_path()?;
    let existing = match std::fs::read_to_string(&path) {
        Ok(existing) => existing,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(format!("无法读取 {}：{error}", path.display())),
    };
    let remaining = remove_hook_config(&existing)?;
    std::fs::write(&path, format!("{remaining}\n"))
        .map_err(|error| format!("无法写入 {}：{error}", path.display()))
}

fn hooks_config_path() -> Result<PathBuf, String> {
    if let Some(codex_home) = std::env::var_os("CODEX_HOME") {
        return Ok(PathBuf::from(codex_home).join("hooks.json"));
    }
    std::env::var_os("USERPROFILE")
        .map(PathBuf::from)
        .map(|path| path.join(".codex").join("hooks.json"))
        .ok_or_else(|| "无法定位 Codex 配置目录".to_string())
}

fn data_directory() -> Result<PathBuf, String> {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .map(|path| path.join("CodexQuota").join("0.4.0"))
        .ok_or_else(|| "无法定位 LOCALAPPDATA".to_string())
}

fn start_quota_monitor(
    runtime: &Arc<Runtime>,
    publisher: HostPublisher,
    mut refresh_requests: tokio::sync::watch::Receiver<u64>,
    initial_collector: Option<QuotaCollector>,
    snapshot_path: PathBuf,
    initial_quota: Option<QuotaSnapshot>,
    quota_state: Arc<RwLock<QuotaSnapshot>>,
    hook_spool: HookEventSpool,
) -> JoinHandle<()> {
    runtime.spawn(async move {
        let mut collector = initial_collector;
        let mut current_quota = initial_quota.unwrap_or_else(|| unavailable_payload().quota);
        let mut last_trusted_quota = if matches!(current_quota.link.codex, CodexLinkStatus::Ok) {
            Some(current_quota.clone())
        } else {
            None
        };
        let mut current_tasks = initial_tasks(now_ms());
        let mut task_runtime = HookTaskRuntime::new();
        let mut quota_interval = tokio::time::interval(std::time::Duration::from_secs(5));
        let mut direct_reset_interval =
            tokio::time::interval(std::time::Duration::from_secs(15 * 60));
        let mut hook_interval = tokio::time::interval(std::time::Duration::from_millis(200));
        loop {
            tokio::select! {
                changed = refresh_requests.changed() => {
                    if changed.is_err() {
                        return;
                    }
                    if collector.is_none() {
                        collector = QuotaCollector::discover().ok();
                    }
                    if let Some(current) = collector.clone() {
                        if let Ok((freshness, refreshed_quota)) = tokio::task::spawn_blocking(move || {
                            let now = Utc::now();
                            let freshness = current.refresh_upstream_freshness(now);
                            let quota = current.collect(now).ok();
                            (freshness, quota)
                        })
                        .await
                        {
                            if let Some(quota) = refreshed_quota {
                                if matches!(quota.link.codex, CodexLinkStatus::Ok) {
                                    let _ = save_cached_snapshot(&snapshot_path, &quota);
                                    last_trusted_quota = Some(quota.clone());
                                }
                                current_quota = quota;
                            } else {
                                current_quota.upstream_freshness = freshness;
                            }
                            if let Ok(mut latest) = quota_state.write() {
                                *latest = current_quota.clone();
                            }
                            publisher
                                .publish(SyncPayload {
                                    quota: current_quota.clone(),
                                    tasks: current_tasks.clone(),
                                })
                                .await;
                        }
                    }
                }
                _ = direct_reset_interval.tick() => {
                    if collector.is_none() {
                        collector = QuotaCollector::discover().ok();
                    }
                    if let Some(current) = collector.clone() {
                        // This is intentionally independent from the 5-second cache
                        // monitor: direct credential use is bounded to startup and a
                        // low-frequency refresh, never repeated on each UI update.
                        if let Ok(freshness) = tokio::task::spawn_blocking(move || {
                            current.refresh_upstream_freshness(Utc::now())
                        })
                        .await
                        {
                            current_quota.upstream_freshness = freshness;
                            if let Ok(mut latest) = quota_state.write() {
                                *latest = current_quota.clone();
                            }
                            publisher
                                .publish(SyncPayload {
                                    quota: current_quota.clone(),
                                    tasks: current_tasks.clone(),
                                })
                                .await;
                        }
                    }
                }
                _ = quota_interval.tick() => {
                    if collector.is_none() {
                        collector = QuotaCollector::discover().ok();
                    }
                    let Some(current) = collector.clone() else {
                        continue;
                    };
                    let result = tokio::task::spawn_blocking(move || current.collect(Utc::now())).await;
                    match result {
                        Ok(Ok(quota)) => {
                            if matches!(quota.link.codex, CodexLinkStatus::Ok) {
                                let _ = save_cached_snapshot(&snapshot_path, &quota);
                                last_trusted_quota = Some(quota.clone());
                            }
                            current_quota = quota;
                        }
                        Ok(Err(_)) | Err(_) => {
                            let Some(mut stale) = last_trusted_quota.clone() else {
                                continue;
                            };
                            stale.generated_at = Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true);
                            stale.source_status = QuotaSourceStatus::Partial;
                            stale.link.codex = CodexLinkStatus::Stale;
                            current_quota = stale;
                        }
                    }
                    if let Ok(mut latest) = quota_state.write() {
                        *latest = current_quota.clone();
                    }
                    publisher
                        .publish(SyncPayload {
                            quota: current_quota.clone(),
                            tasks: current_tasks.clone(),
                        })
                        .await;
                }
                _ = hook_interval.tick() => {
                    if let Ok(Some(tasks)) =
                        task_runtime.poll(&hook_spool, now_ms(), chatgpt_is_foreground())
                    {
                        current_tasks = tasks;
                        publisher
                            .publish(SyncPayload {
                                quota: current_quota.clone(),
                                tasks: current_tasks.clone(),
                            })
                            .await;
                    }
                }
            }
        }
    })
}

fn show_message(title: &str, body: &str, flags: MESSAGEBOX_STYLE) {
    unsafe {
        MessageBoxW(
            null_mut(),
            wide(body).as_ptr(),
            wide(title).as_ptr(),
            MB_OK | flags,
        );
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

fn copy_wide<const N: usize>(target: &mut [u16; N], value: &str) {
    let encoded = wide(value);
    let count = encoded.len().min(N);
    target[..count].copy_from_slice(&encoded[..count]);
}

const fn rgb(red: u8, green: u8, blue: u8) -> u32 {
    red as u32 | ((green as u32) << 8) | ((blue as u32) << 16)
}

#[cfg(test)]
mod ui_contract_tests {
    use super::*;

    #[test]
    fn lightweight_windows_surfaces_stay_compact() {
        assert_eq!((PAIRING_WIDTH, PAIRING_HEIGHT), (460, 620));
        assert_eq!((DIAGNOSTICS_WIDTH, DIAGNOSTICS_HEIGHT), (480, 340));
    }

    #[test]
    fn status_palette_matches_android_design_system() {
        assert_eq!(UI_PRIMARY, rgb(23, 111, 175));
        assert_eq!(UI_WAITING, rgb(25, 128, 82));
        assert_eq!(UI_CACHED, rgb(104, 118, 128));
    }

    #[test]
    fn diagnostics_reports_observable_phone_and_upstream_states() {
        assert_eq!(phone_connection_label(0), "未连接");
        assert_eq!(phone_connection_label(1), "已连接");
        let mut freshness = UpstreamFreshness::default();
        assert_eq!(upstream_usage_label(&freshness, false).0, "待同步");
        freshness.usage.status = UpstreamFreshnessStatus::Cached;
        assert_eq!(upstream_usage_label(&freshness, false).0, "缓存");
        freshness.usage.status = UpstreamFreshnessStatus::Current;
        assert_eq!(upstream_usage_label(&freshness, false).0, "已同步");
        assert_eq!(upstream_usage_label(&freshness, true).0, "同步中");
    }

    #[test]
    fn diagnostics_cache_age_uses_the_shared_minute_hour_day_and_week_units() {
        assert_eq!(elapsed_age_label(0), "刚刚");
        assert_eq!(elapsed_age_label(59 * 60_000), "59分");
        assert_eq!(elapsed_age_label(415 * 60_000), "6小时");
        assert_eq!(elapsed_age_label(3 * 24 * 60 * 60_000), "3天");
        assert_eq!(elapsed_age_label(9 * 24 * 60 * 60_000), "1周");
        assert_eq!(elapsed_age_label(800 * 24 * 60 * 60_000), "99周+");
    }

    #[test]
    fn diagnostics_exposes_only_safe_upstream_confirmation_guidance() {
        assert_eq!(
            upstream_confirmation_guidance(Some(UpstreamConfirmationErrorCode::AuthRejected)),
            "最近确认失败 AUTH_REJECTED：请重新登录 Codex"
        );
        assert_eq!(
            upstream_confirmation_guidance(Some(UpstreamConfirmationErrorCode::Network)),
            "最近确认失败 NETWORK：检查网络或代理"
        );
        assert!(
            !upstream_confirmation_guidance(Some(UpstreamConfirmationErrorCode::ResponseFormat))
                .contains("token")
        );
    }

    #[test]
    fn diagnostics_refresh_action_stays_outside_the_status_card() {
        let client = RECT {
            left: 0,
            top: 0,
            right: 480,
            bottom: 301,
        };
        let panel = diagnostics_panel_rect(client);
        let button = diagnostics_refresh_button_rect(client);
        assert!(panel.bottom < button.top);
        assert!(button.right <= client.right - 24);
    }

    #[test]
    fn tray_menu_keeps_only_the_lightweight_actions_visible() {
        assert_ne!(MENU_PAIR, MENU_DIAGNOSTICS);
        assert_ne!(MENU_REPAIR_HOOK, MENU_DIAGNOSTICS);
        assert_ne!(MENU_REFRESH_STATUS, MENU_DIAGNOSTICS);
    }

    #[test]
    fn tray_menu_shows_five_hour_and_weekly_quota() {
        let mut quota = unavailable_payload().quota;
        assert_eq!(tray_quota_labels(&quota), ["5小时额度 --", "周额度 --"]);
        quota.windows = vec![
            codex_quota_windows_core::QuotaWindow {
                id: "five-hour".to_string(),
                name: "five_hour".to_string(),
                window_minutes: 300,
                remaining_percent: Some(68),
                resets_at: "2099-08-04T20:00:00Z".to_string(),
                status: codex_quota_windows_core::QuotaWindowStatus::Current,
            },
            codex_quota_windows_core::QuotaWindow {
                id: "weekly".to_string(),
                name: "weekly".to_string(),
                window_minutes: 10_080,
                remaining_percent: Some(81),
                resets_at: "2099-08-09T00:00:00Z".to_string(),
                status: codex_quota_windows_core::QuotaWindowStatus::Current,
            },
        ];

        assert_eq!(tray_quota_labels(&quota), ["5小时额度 68%", "周额度 81%"]);
    }

    #[test]
    fn tray_menu_does_not_present_expired_quota_as_current() {
        let mut quota = unavailable_payload().quota;
        quota.windows = vec![codex_quota_windows_core::QuotaWindow {
            id: "expired-five-hour".to_string(),
            name: "five_hour".to_string(),
            window_minutes: 300,
            remaining_percent: Some(68),
            resets_at: "2020-01-01T00:00:00Z".to_string(),
            status: codex_quota_windows_core::QuotaWindowStatus::Current,
        }];

        assert_eq!(tray_quota_labels(&quota)[0], "5小时额度 --");
    }

    #[test]
    fn pairing_window_uses_android_card_curvature_and_product_guidance() {
        assert_eq!(PAIRING_SURFACE_RADIUS, 32);
        assert_eq!(
            (PAIRING_TITLE_SIZE, PAIRING_BODY_SIZE, PAIRING_SUPPORT_SIZE),
            (20, 12, 10)
        );
        assert_eq!((PAIRING_GUIDANCE_SIZE, PAIRING_CODE_SIZE), (13, 22));
        assert_eq!((PAIRING_QR_SIZE, PAIRING_QR_TOP), (286, 96));
        assert_eq!(PAIRING_HOOK_GUIDANCE, "使用手机端「Codex额度」App 扫码");
        assert_eq!(PAIRING_APP_GUIDANCE, "打开 App → 设置 → 连接电脑");
        assert_eq!(PAIRING_NETWORK_GUIDANCE, "确保手机和电脑连接同一局域网");
        assert_eq!(PAIRING_TUTORIAL_BUTTON_LABEL, "配对教学");
        assert_eq!(
            PAIRING_TUTORIAL_STEPS,
            [
                "1. 在电脑托盘菜单中选择「安装/修复任务 Hook」",
                "2. 在 ChatGPT 中打开「设置 → 钩子」，并信任全部钩子",
                "3. 在手机端打开「Codex额度」App，进入「设置 → 连接电脑」",
                "4. 扫描二维码或输入 6 位配对码完成配对",
            ]
        );
        assert_eq!(
            HOOK_INSTALLED_GUIDANCE,
            "任务 Hook 已安装或修复。\n请在 ChatGPT 中打开「设置 → 钩子」，并信任全部钩子。"
        );
    }

    #[test]
    fn pairing_tutorial_button_stays_inside_the_window() {
        let client = RECT {
            left: 0,
            top: 0,
            right: 460,
            bottom: 521,
        };
        let button = pairing_tutorial_button_rect(client);
        assert!(button.left >= 24);
        assert!(button.right <= client.right - 24);
        assert!(button.top >= 0);
        assert!(button.bottom <= client.bottom - 16);
    }

    #[test]
    fn pairing_window_reuses_the_current_offer_until_refresh_is_explicit() {
        assert_eq!(pairing_open_action(true), PairingOpenAction::FocusExisting);
        assert_eq!(pairing_open_action(false), PairingOpenAction::CreateOffer);
    }

    #[test]
    fn pairing_window_bottom_content_does_not_overlap_actions() {
        let client = RECT {
            left: 0,
            top: 0,
            right: 446,
            bottom: 581,
        };
        let layout = pairing_window_bottom_layout(client, 402);
        assert!(402 < layout.app_guidance.top);
        assert!(layout.app_guidance.bottom < layout.manual_guidance.top);
        assert!(layout.manual_guidance.bottom < layout.pairing_code.top);
        assert!(layout.pairing_code.bottom < layout.security_code.top);
        assert!(layout.security_code.bottom < layout.network_guidance.top);
        assert!(layout.network_guidance.bottom < layout.refresh_button.top);
        assert_eq!(layout.refresh_button.top, layout.tutorial_button.top);
        assert!(layout.refresh_button.right < layout.tutorial_button.left);
    }

    #[test]
    fn pairing_tutorial_uses_product_chrome_without_a_system_close_button() {
        assert_ne!(PAIRING_TUTORIAL_WINDOW_STYLE & WS_SYSMENU, WS_SYSMENU);
        assert_eq!(PAIRING_TUTORIAL_WINDOW_STYLE & WS_POPUP, WS_POPUP);
    }

    #[test]
    fn auxiliary_windows_open_centered_in_the_work_area() {
        let work_area = RECT {
            left: 0,
            top: 0,
            right: 1920,
            bottom: 1040,
        };
        let origin = centered_window_origin(work_area, 480, 340);
        assert_eq!((origin.x, origin.y), (720, 350));
    }

    #[test]
    fn pairing_tutorial_has_an_explicit_close_button_in_its_header() {
        let client = RECT {
            left: 0,
            top: 0,
            right: PAIRING_TUTORIAL_WIDTH,
            bottom: PAIRING_TUTORIAL_HEIGHT,
        };
        let close = pairing_tutorial_header_close_button_rect(client);
        assert!(close.left >= client.right - 56);
        assert!(close.top >= 8);
        assert!(close.bottom <= 48);
    }

    #[test]
    fn diagnostics_rows_are_centered_as_a_group_inside_the_status_card() {
        let panel = RECT {
            left: 24,
            top: 94,
            right: 456,
            bottom: 271,
        };
        for row in 0..3 {
            let text = diagnostics_row_text_rect(panel, row);
            assert_eq!((text.top + text.bottom) / 2, panel.top + 20 + row * 48);
        }
    }
}
