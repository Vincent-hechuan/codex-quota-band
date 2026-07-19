import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { FORMAL_ASTROBOX_PLUGIN_NAME } from "../src/desktop/pairing-deeplink.js";

async function readJson(relativeUrl) {
  return JSON.parse(await readFile(new URL(relativeUrl, import.meta.url), "utf8"));
}

test("the QR pairing release advances desktop and AstroBox without changing the Band UI", async () => {
  const [desktopPackage, pluginManifest, bandPackage, bandManifest] = await Promise.all([
    readJson("../package.json"),
    readJson("../astrobox-plugin/manifest.json"),
    readJson("../band-app/package.json"),
    readJson("../band-app/src/manifest.json"),
  ]);

  assert.equal(desktopPackage.version, "0.3.0");
  assert.equal(pluginManifest.version, "0.2.0");
  assert.equal(pluginManifest.name, FORMAL_ASTROBOX_PLUGIN_NAME);
  assert.ok(pluginManifest.permissions.includes("register_deeplink_action"));
  assert.equal(bandPackage.version, "0.2.1");
  assert.equal(bandManifest.versionName, "0.2.1");
  assert.equal(bandManifest.versionCode, 21);
});
