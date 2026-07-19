import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { withBuiltPage } from "./support/built-page-harness.js";

const builtManifestUrl = new URL("../build/manifest-watch.json", import.meta.url);

function rulesForClass(styles, className) {
  return styles.find(([selectors]) =>
    selectors.some(([selectorType, value]) => selectorType === 0 && value === className),
  )?.[1];
}

function collectTags(node, tags = []) {
  if (!node) return tags;
  if (Array.isArray(node)) {
    for (const child of node) collectTags(child, tags);
    return tags;
  }
  if (typeof node === "object") {
    if (typeof node.tag === "string") tags.push(node.tag);
    collectTags(node.children, tags);
  }
  return tags;
}

test("built app uses the full Xiaomi Smart Band 10 canvas", async () => {
  const manifest = JSON.parse(await readFile(builtManifestUrl, "utf8"));
  assert.equal(manifest.config.designWidth, 212);

  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const pageRules = rulesForClass(pageExports.default.style, "page");
    const contentRules = rulesForClass(pageExports.default.style, "content");

    assert.deepEqual(
      { width: pageRules?.width, height: pageRules?.height },
      { width: "212px", height: "520px" },
    );
    assert.deepEqual(
      { width: contentRules?.width, height: contentRules?.height },
      { width: "212px", height: "520px" },
    );
  });
});

test("built replacement package has the unified 0.3.0 release identity", async () => {
  const manifest = JSON.parse(await readFile(builtManifestUrl, "utf8"));
  assert.equal(manifest.versionName, "0.3.0");
  assert.equal(manifest.versionCode, 30);
});

test("built Band 10 page restores the readable v0.1.3 hierarchy with a compact clock header", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const styles = pageExports.default.style;
    const frame = pageExports.default.template({ ...pageExports.default.private });
    const tags = collectTags(frame);

    assert.equal(tags[0], "div");
    assert.equal(tags.filter((tag) => tag === "progress").length, 0);
    assert.equal(rulesForClass(styles, "ring-track"), undefined);
    assert.equal(rulesForClass(styles, "clock-time")?.fontSize, "30px");
    assert.deepEqual(
      {
        width: rulesForClass(styles, "sync-pill")?.width,
        height: rulesForClass(styles, "sync-pill")?.height,
      },
      { width: "138px", height: "40px" },
    );
    assert.equal(rulesForClass(styles, "section-label")?.fontSize, "20px");
    assert.equal(rulesForClass(styles, "quota-number")?.fontSize, "76px");
    assert.equal(rulesForClass(styles, "divider")?.height, "2px");
    assert.equal(rulesForClass(styles, "reset-number")?.fontSize, "68px");
    assert.equal(rulesForClass(styles, "reset-unit")?.fontSize, "20px");
    assert.equal(rulesForClass(styles, "reset-next")?.fontSize, "20px");
  });
});
