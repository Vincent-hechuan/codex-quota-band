import assert from "node:assert/strict";
import test from "node:test";
import {
  createAstroBoxPairingDeepLink,
  prioritizePairingAddresses,
} from "../src/desktop/pairing-deeplink.js";
import {
  createPairingWindowContent,
  createPairingWindowHtml,
  showPairingWindow,
} from "../src/desktop/pairing-window.js";

test("the pairing QR targets the formal AstroBox plugin with a closed versioned payload", () => {
  const deepLink = createAstroBoxPairingDeepLink({
    pluginName: "Codex 额度桥接",
    endpoints: [
      "http://192.168.3.2:17321",
      "http://10.0.0.8:17321",
    ],
    code: "123456",
  });

  const url = new URL(deepLink);
  assert.equal(url.protocol, "astrobox:");
  assert.equal(url.hostname, "open");
  assert.equal(url.searchParams.get("source"), "plugdata");
  assert.equal(url.searchParams.get("name"), "Codex 额度桥接");

  assert.deepEqual(JSON.parse(url.searchParams.get("payload")), {
    protocolVersion: 1,
    kind: "codex-quota-pairing",
    baseUrls: [
      "http://192.168.3.2:17321",
      "http://10.0.0.8:17321",
    ],
    code: "123456",
  });
  assert.equal(url.searchParams.has("pluginName"), false);
  assert.equal(url.searchParams.has("data"), false);
});

test("the desktop refuses malformed or non-private pairing material", () => {
  const valid = {
    pluginName: "Codex 额度桥接",
    endpoints: ["http://192.168.3.2:17321"],
    code: "123456",
  };

  for (const invalid of [
    { ...valid, pluginName: "" },
    { ...valid, endpoints: [] },
    { ...valid, endpoints: ["http://8.8.8.8:17321"] },
    { ...valid, endpoints: ["https://192.168.3.2:17321"] },
    { ...valid, code: "12345" },
  ]) {
    assert.throws(() => createAstroBoxPairingDeepLink(invalid));
  }
});

test("home LAN addresses are attempted before common VPN and link-local ranges", () => {
  assert.deepEqual(
    prioritizePairingAddresses([
      "10.8.0.2",
      "169.254.10.2",
      "192.168.3.2",
      "172.20.0.2",
      "192.168.31.8",
    ]),
    [
      "192.168.3.2",
      "192.168.31.8",
      "172.20.0.2",
      "10.8.0.2",
      "169.254.10.2",
    ],
  );
});

test("the Windows pairing window makes QR primary and manual details copyable", () => {
  const html = createPairingWindowHtml({
    qrDataUrl: "data:image/png;base64,TEST",
    code: "123456",
    endpoints: ["http://192.168.3.2:17321", "http://10.0.0.8:17321"],
    expiresAt: "2026-07-18T02:05:00.000Z",
  });

  assert.match(html, /扫码配对/);
  assert.match(html, /data:image\/png;base64,TEST/);
  assert.match(html, /打开系统相机扫描/);
  assert.match(html, /123456/);
  assert.match(html, /192\.168\.3\.2:17321/);
  assert.match(html, /readonly/);
  assert.match(html, /复制/);
  assert.doesNotMatch(html, /ChatGPT.*(?:令牌|token)/i);
});

test("the pairing window encodes the current one-time session into its QR", async () => {
  let encodedText;
  const html = await createPairingWindowContent({
    pluginName: "Codex 额度桥接",
    pairing: {
      code: "654321",
      endpoints: ["http://192.168.3.2:17321"],
      expiresAt: "2026-07-18T02:05:00.000Z",
    },
    qrEncoder: async (text) => {
      encodedText = text;
      return "data:image/png;base64,FORMAL";
    },
  });

  const url = new URL(encodedText);
  assert.equal(url.searchParams.get("source"), "plugdata");
  assert.equal(JSON.parse(url.searchParams.get("payload")).code, "654321");
  assert.match(html, /data:image\/png;base64,FORMAL/);
  assert.match(html, /654321/);
});

test("the production QR encoder emits an embedded PNG", async () => {
  const html = await createPairingWindowContent({
    pluginName: "Codex 额度桥接",
    pairing: {
      code: "654321",
      endpoints: ["http://192.168.3.2:17321"],
      expiresAt: "2026-07-18T02:05:00.000Z",
    },
  });
  const qr = html.match(/src="(data:image\/png;base64,[^"]+)"/)?.[1];
  assert.ok(qr);
  assert.ok(Buffer.from(qr.split(",", 2)[1], "base64").length > 1_000);
});

test("the pairing window runs isolated and blocks navigation", async () => {
  const sequence = [];
  let options;
  let loadedUrl;
  let shown = false;
  let navigationHandler;
  let openHandler;
  class FakeWindow {
    constructor(value) {
      options = value;
      this.webContents = {
        on(event, handler) {
          if (event === "will-navigate") {
            sequence.push("navigation-handler");
            navigationHandler = handler;
          }
        },
        setWindowOpenHandler(handler) {
          sequence.push("open-handler");
          openHandler = handler;
        },
      };
    }
    async loadURL(value) {
      sequence.push("load");
      loadedUrl = value;
    }
    show() {
      sequence.push("show");
      shown = true;
    }
  }

  const window = await showPairingWindow({
    BrowserWindow: FakeWindow,
    pluginName: "Codex 额度桥接",
    pairing: {
      code: "654321",
      endpoints: ["http://192.168.3.2:17321"],
      expiresAt: "2026-07-18T02:05:00.000Z",
    },
    qrEncoder: async () => "data:image/png;base64,FORMAL",
  });

  assert.ok(window instanceof FakeWindow);
  assert.equal(options.webPreferences.nodeIntegration, false);
  assert.equal(options.webPreferences.contextIsolation, true);
  assert.equal(options.webPreferences.sandbox, true);
  assert.match(loadedUrl, /^data:text\/html;charset=utf-8,/);
  assert.deepEqual(sequence, ["open-handler", "load", "navigation-handler", "show"]);
  assert.deepEqual(openHandler(), { action: "deny" });
  let prevented = false;
  navigationHandler({ preventDefault: () => (prevented = true) });
  assert.equal(prevented, true);
  assert.equal(shown, true);
});
