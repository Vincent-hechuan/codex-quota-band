import assert from "node:assert/strict";
import test from "node:test";
import {
  loadTrayIcon,
  resolveTrayIconPath,
} from "../src/desktop/tray-icon.js";

test("tray icon resolves from the app during development and resources after packaging", () => {
  assert.equal(
    resolveTrayIconPath({
      isPackaged: false,
      appPath: "C:\\source\\codex-quota",
      resourcesPath: "C:\\installed\\resources",
    }),
    "C:\\source\\codex-quota\\build\\tray-icon.png",
  );
  assert.equal(
    resolveTrayIconPath({
      isPackaged: true,
      appPath: "C:\\installed\\resources\\app.asar",
      resourcesPath: "C:\\installed\\resources",
    }),
    "C:\\installed\\resources\\tray-icon.png",
  );
});

test("tray icon loader rejects an empty native image", () => {
  assert.throws(
    () => loadTrayIcon({
      filePath: "C:\\missing\\tray-icon.png",
      nativeImage: {
        createFromPath: () => ({ isEmpty: () => true }),
      },
    }),
    /could not be loaded/i,
  );
});

test("tray icon loader returns a valid native image", () => {
  const expected = { isEmpty: () => false };
  assert.equal(
    loadTrayIcon({
      filePath: "C:\\installed\\resources\\tray-icon.png",
      nativeImage: { createFromPath: () => expected },
    }),
    expected,
  );
});
