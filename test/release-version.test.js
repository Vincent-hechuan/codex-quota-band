import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { FORMAL_ASTROBOX_PLUGIN_NAME } from "../src/desktop/pairing-deeplink.js";

async function readJson(relativeUrl) {
  return JSON.parse(await readFile(new URL(relativeUrl, import.meta.url), "utf8"));
}

test("all public components use the same release version", async () => {
  const [desktopPackage, pluginManifest, bandPackage, bandManifest] = await Promise.all([
    readJson("../package.json"),
    readJson("../astrobox-plugin/manifest.json"),
    readJson("../band-app/package.json"),
    readJson("../band-app/src/manifest.json"),
  ]);

  assert.equal(desktopPackage.version, "0.3.0");
  assert.equal(pluginManifest.version, desktopPackage.version);
  assert.equal(pluginManifest.name, FORMAL_ASTROBOX_PLUGIN_NAME);
  assert.ok(pluginManifest.permissions.includes("register_deeplink_action"));
  assert.equal(bandPackage.version, desktopPackage.version);
  assert.equal(bandManifest.versionName, desktopPackage.version);
  assert.equal(bandManifest.versionCode, 30);
});
