import QRCode from "qrcode";
import { createAstroBoxPairingDeepLink } from "./pairing-deeplink.js";

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

export async function createPairingWindowContent({
  pluginName,
  pairing,
  qrEncoder = QRCode.toDataURL,
}) {
  const deepLink = createAstroBoxPairingDeepLink({
    pluginName,
    endpoints: pairing.endpoints,
    code: pairing.code,
  });
  const qrDataUrl = await qrEncoder(deepLink, {
    errorCorrectionLevel: "M",
    margin: 2,
    width: 512,
    color: { dark: "#071014", light: "#FFFFFFFF" },
  });
  return createPairingWindowHtml({
    qrDataUrl,
    code: pairing.code,
    endpoints: pairing.endpoints,
    expiresAt: pairing.expiresAt,
  });
}

export async function showPairingWindow({
  BrowserWindow,
  pluginName,
  pairing,
  qrEncoder,
  icon,
}) {
  const html = await createPairingWindowContent({
    pluginName,
    pairing,
    qrEncoder,
  });
  const window = new BrowserWindow({
    width: 460,
    height: 660,
    useContentSize: true,
    resizable: false,
    maximizable: false,
    fullscreenable: false,
    autoHideMenuBar: true,
    backgroundColor: "#07090c",
    show: false,
    title: "Codex 额度 · 扫码配对",
    ...(icon ? { icon } : {}),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  window.webContents.on("will-navigate", (event) => event.preventDefault());
  window.show();
  return window;
}

export function createPairingWindowHtml({
  qrDataUrl,
  code,
  endpoints,
  expiresAt,
}) {
  if (!qrDataUrl.startsWith("data:image/png;base64,")) {
    throw new TypeError("pairing QR must be a PNG data URL");
  }
  const addressText = endpoints.join("\n");
  const expiration = new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(expiresAt));

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>扫码配对</title>
  <style>
    :root { color-scheme: dark; font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100vh; color: #f7fbff; background: radial-gradient(circle at 50% 10%, #14313a 0, #0b1116 42%, #07090c 100%); }
    main { width: min(100%, 440px); margin: 0 auto; padding: 28px 30px 24px; text-align: center; }
    h1 { margin: 0; font-size: 28px; line-height: 1.2; letter-spacing: .02em; }
    .hint { margin: 10px 0 18px; color: #aebbc6; font-size: 15px; }
    .qr-shell { width: 276px; height: 276px; margin: 0 auto; padding: 12px; border: 1px solid rgba(79, 231, 207, .36); border-radius: 28px; background: #fff; box-shadow: 0 18px 56px rgba(0, 214, 190, .14); }
    .qr-shell img { display: block; width: 100%; height: 100%; }
    .expiry { margin: 14px 0 20px; color: #68e7d0; font-size: 14px; }
    details { border-top: 1px solid #26323b; padding-top: 15px; text-align: left; }
    summary { cursor: pointer; color: #c8d2da; font-size: 14px; text-align: center; user-select: none; }
    .field { margin-top: 13px; }
    .label { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; color: #8f9ca7; font-size: 12px; }
    input, textarea { width: 100%; border: 1px solid #2c3b45; border-radius: 10px; padding: 10px 12px; color: #f6f9fb; background: #10171d; font: 14px/1.45 Consolas, monospace; resize: none; outline: none; }
    textarea { min-height: 64px; }
    button { border: 0; padding: 3px 0; color: #59dfc8; background: transparent; cursor: pointer; font: inherit; }
    .safety { margin: 15px 0 0; color: #73818c; font-size: 12px; line-height: 1.5; text-align: center; }
  </style>
</head>
<body>
  <main>
    <h1>扫码配对</h1>
    <p class="hint">打开系统相机扫描，随后在 AstroBox 中打开</p>
    <div class="qr-shell"><img alt="AstroBox 配对二维码" src="${escapeHtml(qrDataUrl)}"></div>
    <p class="expiry">一次性配对 · ${escapeHtml(expiration)} 前有效</p>
    <details>
      <summary>无法扫码？展开手动信息</summary>
      <div class="field">
        <div class="label"><span>临时配对码</span><button type="button" data-copy="pairing-code">复制</button></div>
        <input id="pairing-code" readonly value="${escapeHtml(code)}">
      </div>
      <div class="field">
        <div class="label"><span>Windows 私网地址</span><button type="button" data-copy="pairing-addresses">复制</button></div>
        <textarea id="pairing-addresses" readonly>${escapeHtml(addressText)}</textarea>
      </div>
    </details>
    <p class="safety">二维码不包含账号凭据；临时码成功使用一次或到期后立即失效。</p>
  </main>
  <script>
    document.addEventListener("click", async (event) => {
      const button = event.target.closest("[data-copy]");
      if (!button) return;
      const field = document.getElementById(button.dataset.copy);
      field.select();
      try {
        await navigator.clipboard.writeText(field.value);
      } catch {
        document.execCommand("copy");
      }
      button.textContent = "已复制";
    });
  </script>
</body>
</html>`;
}
