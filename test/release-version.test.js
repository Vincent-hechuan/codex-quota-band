import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { FORMAL_ASTROBOX_PLUGIN_NAME } from "../src/desktop/pairing-deeplink.js";

async function readJson(relativeUrl) {
  return JSON.parse(await readFile(new URL(relativeUrl, import.meta.url), "utf8"));
}

test("legacy Electron and AstroBox components remain on the same historical version", async () => {
  const [desktopPackage, pluginManifest] = await Promise.all([
    readJson("../package.json"),
    readJson("../astrobox-plugin/manifest.json"),
  ]);

  assert.equal(desktopPackage.version, "0.3.1");
  assert.equal(pluginManifest.version, desktopPackage.version);
  assert.equal(pluginManifest.name, FORMAL_ASTROBOX_PLUGIN_NAME);
  assert.ok(pluginManifest.permissions.includes("register_deeplink_action"));
});

test("current Windows, Android, and Band packages share the formal 0.6.1 version", async () => {
  const [windowsCargo, androidGradle, bandPackage, bandManifest] = await Promise.all([
    readFile(new URL("../windows-native/Cargo.toml", import.meta.url), "utf8"),
    readFile(new URL("../android-app/app/build.gradle.kts", import.meta.url), "utf8"),
    readJson("../band-app/package.json"),
    readJson("../band-app/src/manifest.json"),
  ]);

  const releaseVersion = "0.6.1";
  assert.match(windowsCargo, new RegExp(`^version\\s*=\\s*"${releaseVersion.replaceAll(".", "\\.")}"$`, "m"));
  assert.match(
    windowsCargo,
    /reqwest\s*=\s*\{[^\n]*"system-proxy"[^\n]*\}/,
    "the Windows quota client must inherit standard system and environment proxy settings",
  );
  assert.match(androidGradle, /versionName\s*=\s*"0\.6\.1"/);
  assert.match(
    androidGradle,
    /versionCode\s*=\s*602/,
    "the Android-only 0.6.1 hotfix keeps a monotonic internal install sequence",
  );
  assert.equal(bandPackage.version, releaseVersion);
  assert.equal(bandManifest.versionName, releaseVersion);
  assert.equal(bandManifest.versionCode, 601);
});
