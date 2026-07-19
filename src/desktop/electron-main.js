import { writeFile } from "node:fs/promises";
import { networkInterfaces } from "node:os";
import { join } from "node:path";
import { app, BrowserWindow, dialog, Menu, nativeImage, Tray } from "electron";
import { collectSnapshot } from "../core/snapshot.js";
import { isPrivateNetworkAddress } from "../server/network-access.js";
import { startQuotaServer } from "../server/quota-server.js";
import { openHashedTokenStore } from "../server/token-store.js";
import { discoverCodexDataPaths } from "./data-paths.js";
import { startDesktopService } from "./desktop-service.js";
import { requiresSingleInstanceLock } from "./launch-mode.js";
import {
  FORMAL_ASTROBOX_PLUGIN_NAME,
  prioritizePairingAddresses,
} from "./pairing-deeplink.js";
import { showPairingWindow } from "./pairing-window.js";
import { createSnapshotCache } from "./snapshot-cache.js";
import { openSnapshotFileStore } from "./snapshot-file-store.js";
import { snapshotSummary } from "./snapshot-summary.js";
import { openStartupPreferenceStore } from "./startup-preference-store.js";
import { loadTrayIcon, resolveTrayIconPath } from "./tray-icon.js";

const APP_NAME = "Codex 额度";
const APP_USER_MODEL_ID = "com.tony.codex-quota";
const SERVER_PORT = 17_321;
const REFRESH_MILLISECONDS = 5_000;

app.setName(APP_NAME);
app.setAppUserModelId(APP_USER_MODEL_ID);

if (requiresSingleInstanceLock(process.argv) && !app.requestSingleInstanceLock()) {
  app.quit();
} else {
  void run();
}

async function run() {
  await app.whenReady();

  if (process.argv.includes("--smoke-test")) {
    loadTrayIcon({
      nativeImage,
      filePath: resolveTrayIconPath({
        isPackaged: app.isPackaged,
        appPath: app.getAppPath(),
        resourcesPath: process.resourcesPath,
      }),
    });
    process.stdout.write(`Codex Quota Electron ${process.versions.electron} ready\n`);
    app.quit();
    return;
  }
  const diagnosticServiceTest = process.argv.includes("--diagnostic-service-test");

  const localAppData = process.env.LOCALAPPDATA;
  if (!localAppData) {
    await dialog.showMessageBox({
      type: "error",
      title: APP_NAME,
      message: "无法定位 Windows LOCALAPPDATA 目录。",
    });
    app.quit();
    return;
  }

  const dataPaths = await discoverCodexDataPaths({
    homeDirectory: app.getPath("home"),
    localAppData,
  });
  const tokenStore = await openHashedTokenStore({
    filePath: join(app.getPath("userData"), "app-data", "authorized-devices.json"),
  });
  const snapshotStore = diagnosticServiceTest
    ? { load: async () => null, save: async () => {} }
    : await openSnapshotFileStore({
        filePath: join(app.getPath("userData"), "app-data", "last-snapshot-v1.json"),
      });
  const startupPreferenceStore = diagnosticServiceTest
    ? { loadOrInitialize: async () => false, save: async () => {} }
    : await openStartupPreferenceStore({
        filePath: join(app.getPath("userData"), "app-data", "preferences.json"),
      });
  const startupEnabled = await startupPreferenceStore.loadOrInitialize();
  if (!diagnosticServiceTest) {
    app.setLoginItemSettings({ openAtLogin: startupEnabled });
  }
  const snapshotCache = createSnapshotCache({
    collector: () => collectSnapshot(dataPaths, new Date()),
    initialSnapshot: await snapshotStore.load(),
    onTrustedSnapshot: (snapshot) => snapshotStore.save(snapshot),
  });
  await snapshotCache.refresh();
  snapshotCache.start(REFRESH_MILLISECONDS);

  const desktopService = await startDesktopService({
    serverFactory: startQuotaServer,
    serverOptions: {
      host: diagnosticServiceTest ? "127.0.0.1" : "0.0.0.0",
      port: diagnosticServiceTest ? 0 : SERVER_PORT,
      snapshotProvider: async () => snapshotCache.getSnapshot(),
      logger: ({ event, status }) => {
        process.stdout.write(`${JSON.stringify({
          time: new Date().toISOString(),
          event,
          status,
        })}\n`);
      },
    },
    tokenStore,
    loginItem: {
      async setEnabled(enabled) {
        if (!diagnosticServiceTest) {
          await startupPreferenceStore.save(enabled);
          app.setLoginItemSettings({ openAtLogin: enabled });
        }
      },
    },
    lanAddresses: privateIpv4Addresses,
  });

  if (diagnosticServiceTest) {
    const snapshot = snapshotCache.getSnapshot();
    const report = `${JSON.stringify({
      sourceStatus: snapshot.sourceStatus,
      windows: snapshot.windows.map(({ name, remainingPercent, resetsAt }) => ({
        name,
        remainingPercent,
        resetsAt,
      })),
      resetInventory: {
        status: snapshot.resetInventory.status,
        availableCount: snapshot.resetInventory.availableCount,
        cachedAt: snapshot.resetInventory.cachedAt,
      },
    })}\n`;
    if (process.env.CODEX_QUOTA_DIAGNOSTIC_OUTPUT) {
      await writeFile(process.env.CODEX_QUOTA_DIAGNOSTIC_OUTPUT, report, "utf8");
    } else {
      process.stdout.write(report);
    }
    snapshotCache.close();
    await desktopService.close();
    app.quit();
    return;
  }

  const trayIcon = loadTrayIcon({
    nativeImage,
    filePath: resolveTrayIconPath({
      isPackaged: app.isPackaged,
      appPath: app.getAppPath(),
      resourcesPath: process.resourcesPath,
    }),
  });
  let tray = new Tray(trayIcon);
  let paused = false;
  let shuttingDown = false;
  let menuTimer;
  let pairingWindow;
  let pairingWindowTimer;

  const rebuildMenu = () => {
    const snapshot = snapshotCache.getSnapshot();
    tray.setToolTip(`${APP_NAME} · ${snapshotSummary(snapshot)}`);
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: "服务运行中", enabled: false },
        { label: snapshotSummary(snapshot), enabled: false },
        { type: "separator" },
        {
          label: "显示配对信息…",
          click: async () => {
            const pairing = desktopService.beginPairing();
            if (pairing.endpoints.length === 0) {
              await dialog.showMessageBox({
                type: "warning",
                title: "配对小米 15",
                message: "未找到可用的私有局域网 IPv4 地址。",
                detail: "请确认 Windows 已连接到与手机相同的可信局域网。",
              });
              return;
            }

            clearTimeout(pairingWindowTimer);
            if (pairingWindow && !pairingWindow.isDestroyed()) {
              pairingWindow.close();
            }
            try {
              const nextWindow = await showPairingWindow({
                BrowserWindow,
                pluginName: FORMAL_ASTROBOX_PLUGIN_NAME,
                pairing,
                icon: trayIcon,
              });
              pairingWindow = nextWindow;
              pairingWindowTimer = setTimeout(() => {
                if (!nextWindow.isDestroyed()) nextWindow.close();
              }, Math.max(0, new Date(pairing.expiresAt).getTime() - Date.now()));
              nextWindow.on("closed", () => {
                if (pairingWindow === nextWindow) {
                  pairingWindow = undefined;
                  clearTimeout(pairingWindowTimer);
                  pairingWindowTimer = undefined;
                }
              });
            } catch {
              await dialog.showMessageBox({
                type: "warning",
                title: "配对小米 15",
                message: `二维码生成失败；临时配对码：${pairing.code}`,
                detail: `地址：\n${pairing.endpoints.join("\n")}\n\n有效至：${formatLocalTime(pairing.expiresAt)}\n配对码仅可成功使用一次。`,
              });
            }
          },
        },
        {
          label: "撤销所有已配对设备…",
          click: async () => {
            const result = await dialog.showMessageBox({
              type: "warning",
              buttons: ["取消", "撤销"],
              defaultId: 0,
              cancelId: 0,
              title: APP_NAME,
              message: "撤销后，小米 15 必须重新配对才能读取额度。",
            });
            if (result.response === 1) {
              await desktopService.revokeAll();
            }
          },
        },
        {
          label: "暂停同步",
          type: "checkbox",
          checked: paused,
          click: ({ checked }) => {
            paused = checked;
            if (paused) snapshotCache.pause();
            else snapshotCache.resume(REFRESH_MILLISECONDS);
            rebuildMenu();
          },
        },
        {
          label: "立即刷新",
          enabled: !paused,
          click: async () => {
            await snapshotCache.refresh();
            rebuildMenu();
          },
        },
        { type: "separator" },
        {
          label: "登录 Windows 时自动启动",
          type: "checkbox",
          checked: app.getLoginItemSettings().openAtLogin,
          click: async ({ checked }) => {
            await desktopService.setLoginStartup(checked);
            rebuildMenu();
          },
        },
        { type: "separator" },
        { label: "退出", click: () => app.quit() },
      ]),
    );
  };

  rebuildMenu();
  menuTimer = setInterval(rebuildMenu, REFRESH_MILLISECONDS);

  app.on("second-instance", () => {
    tray.displayBalloon({
      title: APP_NAME,
      content: snapshotSummary(snapshotCache.getSnapshot()),
      iconType: "info",
      noSound: true,
    });
  });
  app.on("window-all-closed", () => {});
  app.on("before-quit", (event) => {
    if (shuttingDown) return;
    event.preventDefault();
    shuttingDown = true;
    clearInterval(menuTimer);
    clearTimeout(pairingWindowTimer);
    snapshotCache.close();
    void desktopService.close().finally(() => {
      tray.destroy();
      tray = null;
      app.quit();
    });
  });
}

function privateIpv4Addresses() {
  const addresses = new Set();
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (
        entry.family === "IPv4" &&
        !entry.internal &&
        isPrivateNetworkAddress(entry.address)
      ) {
        addresses.add(entry.address);
      }
    }
  }
  return prioritizePairingAddresses([...addresses]);
}

function formatLocalTime(value) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(new Date(value));
}
