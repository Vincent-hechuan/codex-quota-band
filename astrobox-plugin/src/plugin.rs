use crate::protocol::{
    error_envelope, pairing_request_body, parse_deeplink_pairing_payload, parse_pairing_token,
    parse_quota_request, snapshot_envelope, validate_base_url,
};
use astrobox_ng_wit::FutureReader;
use astrobox_ng_wit::astrobox::psys_host::{device, dialog, interconnect, register, ui_v3 as ui};
use astrobox_ng_wit::exports::astrobox::psys_plugin::{
    event_v3::{self as event, EventType},
    lifecycle,
};
use serde_json::Value;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use waki::Client;

const QUICK_APP_PACKAGE: &str = "com.codex.quota";
const CONFIGURE_EVENT: &str = "configure-pairing";
const SUBSCRIBE_EVENT: &str = "subscribe-devices";
const FORGET_EVENT: &str = "forget-credentials";
const MAXIMUM_RESPONSE_BYTES: usize = 64 * 1024;

static STATE: OnceLock<Mutex<PluginState>> = OnceLock::new();
static UI_ROOT: OnceLock<Mutex<Option<String>>> = OnceLock::new();

#[derive(Default)]
struct PluginState {
    base_url: Option<String>,
    token: Option<String>,
    status: String,
}

struct CodexQuotaPlugin;

struct IncomingMessage {
    address: String,
    package_name: String,
    data: String,
}

impl PluginState {
    fn configured(&self) -> bool {
        self.base_url.is_some() && self.token.is_some()
    }
}

fn state() -> &'static Mutex<PluginState> {
    STATE.get_or_init(|| {
        Mutex::new(PluginState {
            status: "尚未配对；令牌只保存在本次 AstroBox 进程内。".into(),
            ..PluginState::default()
        })
    })
}

fn set_status(status: impl Into<String>) {
    state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .status = status.into();
    rerender();
}

fn credentials() -> Option<(String, String)> {
    let state = state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    Some((state.base_url.clone()?, state.token.clone()?))
}

fn configure(base_url: String, token: String) {
    let mut state = state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    state.base_url = Some(base_url);
    state.token = Some(token);
    state.status = "配对成功，凭据仅驻内存。".into();
}

fn forget_credentials() {
    let mut state = state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    state.base_url = None;
    state.token = None;
    state.status = "已清除本次进程中的配对凭据。".into();
}

fn parse_incoming(payload: &str) -> IncomingMessage {
    let envelope = serde_json::from_str::<Value>(payload).ok();
    let address = envelope
        .as_ref()
        .and_then(|value| value.get("addr"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let package_name = envelope
        .as_ref()
        .and_then(|value| value.get("pkgName"))
        .and_then(Value::as_str)
        .unwrap_or(QUICK_APP_PACKAGE)
        .to_string();
    let data = envelope
        .as_ref()
        .and_then(|value| value.get("payloadText"))
        .and_then(Value::as_str)
        .map(str::to_string)
        .or_else(|| {
            envelope
                .as_ref()
                .and_then(|value| value.get("payload"))
                .map(|value| {
                    value
                        .as_str()
                        .map(str::to_string)
                        .unwrap_or_else(|| value.to_string())
                })
        })
        .unwrap_or_else(|| payload.to_string());

    IncomingMessage {
        address,
        package_name,
        data,
    }
}

fn register_pair(address: &str) -> bool {
    astrobox_ng_wit::block_on(
        register::register_interconnect_recv(address, QUICK_APP_PACKAGE).into_future(),
    )
    .is_ok()
}

fn register_current_devices() -> (usize, usize) {
    let devices = astrobox_ng_wit::block_on(device::get_connected_device_list().into_future());
    let mut successes = usize::from(register_pair(""));
    for current in &devices {
        if register_pair(&current.addr) {
            successes += 1;
        }
    }
    (devices.len(), successes)
}

fn send_to_band(incoming: &IncomingMessage, message: &str) -> Result<(), String> {
    if incoming.address.is_empty() {
        return Err("missing_device_address".into());
    }
    astrobox_ng_wit::block_on(
        interconnect::send_qaic_message(&incoming.address, &incoming.package_name, message)
            .into_future(),
    )
    .map_err(|_| "band_send_rejected".into())
}

fn request_snapshot(base_url: &str, token: &str) -> Result<String, String> {
    let response = Client::new()
        .get(&format!("{base_url}/v1/snapshot"))
        .header("authorization", format!("Bearer {token}"))
        .connect_timeout(Duration::from_secs(5))
        .send()
        .map_err(|_| "windows_unreachable".to_string())?;
    if response.status_code() != 200 {
        return Err(match response.status_code() {
            401 => "pairing_revoked",
            403 => "private_network_required",
            _ => "windows_response_error",
        }
        .into());
    }
    let bytes = response
        .body()
        .map_err(|_| "snapshot_body_error".to_string())?;
    if bytes.len() > MAXIMUM_RESPONSE_BYTES {
        return Err("snapshot_too_large".into());
    }
    String::from_utf8(bytes).map_err(|_| "snapshot_not_utf8".into())
}

fn pair_with_windows(base_url: &str, code: &str) -> Result<String, String> {
    let body = pairing_request_body(code)?;
    let response = Client::new()
        .post(&format!("{base_url}/v1/pair"))
        .header("content-type", "application/json")
        .body(body.as_bytes())
        .connect_timeout(Duration::from_secs(5))
        .send()
        .map_err(|_| "无法连接 Windows 服务".to_string())?;
    if response.status_code() != 201 {
        return Err(match response.status_code() {
            401 => "配对码错误",
            410 => "配对码已过期或已使用",
            429 => "配对尝试次数已达上限",
            _ => "Windows 拒绝配对",
        }
        .into());
    }
    let bytes = response
        .body()
        .map_err(|_| "无法读取配对响应".to_string())?;
    if bytes.len() > 4_096 {
        return Err("配对响应过大".into());
    }
    let body = String::from_utf8(bytes).map_err(|_| "配对响应格式错误".to_string())?;
    parse_pairing_token(&body)
}

fn prompt(title: &str, content: &str) -> Option<String> {
    use dialog::{DialogButton, DialogInfo, DialogStyle, DialogType};

    let result = astrobox_ng_wit::block_on(
        dialog::show_dialog(
            DialogType::Input,
            DialogStyle::Website,
            &DialogInfo {
                title: title.into(),
                content: content.into(),
                buttons: vec![
                    DialogButton {
                        id: "confirm".into(),
                        primary: true,
                        content: "确认".into(),
                    },
                    DialogButton {
                        id: "cancel".into(),
                        primary: false,
                        content: "取消".into(),
                    },
                ],
            },
        )
        .into_future(),
    );
    if result.clicked_btn_id != "confirm" {
        return None;
    }
    let value = result.input_result.trim().to_string();
    (!value.is_empty()).then_some(value)
}

fn run_pairing_flow() {
    let Some(base_url_input) = prompt(
        "Windows 服务地址",
        "请输入托盘中显示的完整私网地址，例如 http://192.168.31.8:17321",
    ) else {
        set_status("已取消配对。凭据没有变化。");
        return;
    };
    let base_url = match validate_base_url(&base_url_input) {
        Ok(value) => value,
        Err(error) => {
            set_status(format!("地址无效：{error}"));
            return;
        }
    };
    let Some(code) = prompt("临时配对码", "请输入 Windows 托盘显示的 6 位临时配对码")
    else {
        set_status("已取消配对。凭据没有变化。");
        return;
    };

    set_status("正在连接 Windows 并配对…");
    match pair_with_windows(&base_url, &code) {
        Ok(token) => {
            configure(base_url, token);
            let (devices, subscriptions) = register_current_devices();
            set_status(format!(
                "配对成功；检测到 {devices} 个已连接设备，建立 {subscriptions} 个订阅。"
            ));
        }
        Err(error) => set_status(format!("配对失败：{error}")),
    }
}

fn run_deeplink_pairing_flow(payload: &str) {
    let pairing = match parse_deeplink_pairing_payload(payload) {
        Ok(pairing) => pairing,
        Err(_) => {
            set_status("二维码内容无效或已被篡改；没有修改现有配对。");
            return;
        }
    };

    set_status("已收到 Windows 配对二维码，正在尝试局域网地址…");
    let mut last_error = "无法连接 Windows 服务".to_string();
    for base_url in pairing.base_urls {
        match pair_with_windows(&base_url, &pairing.code) {
            Ok(token) => {
                configure(base_url, token);
                let (devices, subscriptions) = register_current_devices();
                set_status(format!(
                    "扫码配对成功；检测到 {devices} 个已连接设备，建立 {subscriptions} 个订阅。"
                ));
                return;
            }
            Err(error) => last_error = error,
        }
    }
    set_status(format!(
        "扫码配对失败：{last_error}。请确认手机与电脑在同一局域网，或使用手动输入。"
    ));
}

fn handle_band_message(incoming: IncomingMessage) {
    let request = match parse_quota_request(&incoming.data) {
        Ok(request) => request,
        Err(_) => {
            set_status("忽略了格式不合法的手环请求。");
            return;
        }
    };
    let Some((base_url, token)) = credentials() else {
        let _ = send_to_band(
            &incoming,
            &error_envelope(&request.nonce, "pairing_required"),
        );
        set_status("手环已请求额度，但插件尚未配对。");
        return;
    };

    match request_snapshot(&base_url, &token)
        .and_then(|snapshot| snapshot_envelope(&snapshot, &request.nonce))
    {
        Ok(message) => match send_to_band(&incoming, &message) {
            Ok(()) => set_status("已向手环发送最新只读额度快照。"),
            Err(_) => set_status("快照已读取，但手环回包失败。"),
        },
        Err(code) => {
            let _ = send_to_band(&incoming, &error_envelope(&request.nonce, &code));
            set_status(format!("额度读取失败：{code}"));
        }
    }
}

fn set_ui_root(element_id: &str) {
    *UI_ROOT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(element_id.to_string());
}

fn rerender() {
    let root = UI_ROOT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if let Some(root) = root {
        render(&root);
    }
}

fn render(element_id: &str) {
    let state = state()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let badge = if state.configured() {
        "已配对（仅本次进程）"
    } else {
        "未配对"
    };
    let title = ui::Element::new(ui::ElementType::P, Some("Codex 额度桥接 v0.3.1"))
        .size(26)
        .text_color("#FFFFFF");
    let status = ui::Element::new(ui::ElementType::P, Some(&state.status))
        .size(17)
        .text_color("#AEB5C0")
        .margin_top(12);
    let safety = ui::Element::new(
        ui::ElementType::P,
        Some("只读 · 不读取 ChatGPT 令牌 · 不支持使用重置额度"),
    )
    .size(15)
    .text_color("#63E6BE")
    .margin_top(10);
    let pairing = ui::Element::new(ui::ElementType::Button, Some("手动输入（高级）"))
        .margin_top(18)
        .padding(10)
        .radius(8)
        .bg("#FFFFFF")
        .text_color("#111318")
        .on(ui::Event::Click, CONFIGURE_EVENT);
    let subscribe = ui::Element::new(ui::ElementType::Button, Some("检测连接并重订阅"))
        .margin_top(10)
        .padding(10)
        .radius(8)
        .border(1, "#59616B")
        .text_color("#FFFFFF")
        .on(ui::Event::Click, SUBSCRIBE_EVENT);
    let forget = ui::Element::new(ui::ElementType::Button, Some("清除本次配对"))
        .margin_top(10)
        .padding(10)
        .radius(8)
        .text_color("#FF9D9D")
        .on(ui::Event::Click, FORGET_EVENT);
    let root = ui::Element::new(ui::ElementType::Div, None)
        .flex()
        .flex_direction(ui::FlexDirection::Column)
        .width_full()
        .padding(20)
        .bg("#111318")
        .child(title)
        .child(
            ui::Element::new(ui::ElementType::Badge, Some(badge))
                .margin_top(10)
                .text_color("#63E6BE"),
        )
        .child(status)
        .child(safety)
        .child(pairing)
        .child(subscribe)
        .child(forget);
    drop(state);
    ui::render(element_id, root);
}

fn immediate_string(value: String) -> FutureReader<String> {
    let (writer, reader) = astrobox_ng_wit::wit_future::new(String::new);
    astrobox_ng_wit::spawn(async move {
        let _ = writer.write(value).await;
    });
    reader
}

fn immediate_unit() -> FutureReader<()> {
    let (writer, reader) = astrobox_ng_wit::wit_future::new::<()>(|| ());
    astrobox_ng_wit::spawn(async move {
        let _ = writer.write(()).await;
    });
    reader
}

impl event::Guest for CodexQuotaPlugin {
    fn on_event(event_type: EventType, event_payload: String) -> FutureReader<String> {
        match event_type {
            EventType::InterconnectMessage => handle_band_message(parse_incoming(&event_payload)),
            EventType::DeeplinkAction => run_deeplink_pairing_flow(&event_payload),
            EventType::DeviceAction => {
                let (devices, subscriptions) = register_current_devices();
                set_status(format!(
                    "设备连接状态变化：{devices} 个设备，{subscriptions} 个有效订阅。"
                ));
            }
            _ => {}
        }
        immediate_string(String::new())
    }

    fn on_ui_event_v3(
        event_id: String,
        event_kind: event::Event,
        _event_payload: String,
    ) -> FutureReader<String> {
        if event_kind == event::Event::Click {
            match event_id.as_str() {
                CONFIGURE_EVENT => run_pairing_flow(),
                SUBSCRIBE_EVENT => {
                    let (devices, subscriptions) = register_current_devices();
                    set_status(format!(
                        "检测到 {devices} 个已连接设备，建立 {subscriptions} 个订阅。"
                    ));
                }
                FORGET_EVENT => {
                    forget_credentials();
                    rerender();
                }
                _ => {}
            }
        }
        immediate_string(String::new())
    }

    fn on_ui_render(element_id: String) -> FutureReader<()> {
        set_ui_root(&element_id);
        render(&element_id);
        immediate_unit()
    }

    fn on_card_render(_card_id: String) -> FutureReader<()> {
        immediate_unit()
    }
}

impl lifecycle::Guest for CodexQuotaPlugin {
    fn on_load() {
        let deeplink_registered =
            astrobox_ng_wit::block_on(register::register_deeplink_action().into_future()).is_ok();
        let (devices, subscriptions) = register_current_devices();
        set_status(if deeplink_registered {
            format!(
                "已准备扫码配对；{devices} 个已连接设备，{subscriptions} 个订阅。请从 Windows 托盘打开二维码。"
            )
        } else {
            format!(
                "DeepLink 注册失败；{devices} 个已连接设备，{subscriptions} 个订阅。请使用手动输入。"
            )
        });
    }
}

astrobox_ng_wit::export!(CodexQuotaPlugin);
