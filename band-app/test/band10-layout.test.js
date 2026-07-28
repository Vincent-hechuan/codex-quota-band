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

function countClass(node, className) {
  if (!node) return 0;
  if (Array.isArray(node)) {
    return node.reduce((total, child) => total + countClass(child, className), 0);
  }
  if (typeof node !== "object") return 0;
  const rawClassList = node.properties?.__opts__?.classList;
  const classList = Array.isArray(rawClassList) ? rawClassList : [];
  return (classList.includes(className) ? 1 : 0) + countClass(node.children, className);
}

test("built app uses the full Xiaomi Smart Band 10 canvas and native vertical pager", async () => {
  const manifest = JSON.parse(await readFile(builtManifestUrl, "utf8"));
  assert.equal(manifest.config.designWidth, 212);

  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const pageRules = rulesForClass(pageExports.default.style, "page");
    const swiperRules = rulesForClass(pageExports.default.style, "page-swiper");

    assert.deepEqual(
      { width: pageRules?.width, height: pageRules?.height },
      { width: "212px", height: "520px" },
    );
    assert.deepEqual(
      { width: swiperRules?.width, height: swiperRules?.height },
      { width: "212px", height: "520px" },
    );
    assert.equal(swiperRules?.indicatorSize, "7px");
    assert.equal(swiperRules?.indicatorRight, "10px");
    assert.equal(swiperRules?.indicatorTop, "249px");
  });
});

test("built Band 10 page uses two vertically swipable pages with one accent dot per task", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const styles = pageExports.default.style;
    const frame = pageExports.default.template({
      ...pageExports.default.private,
      hasTaskItems: true,
      taskItems: [
        { title: "允许写入文件", statusText: "需要授权", tone: "danger", showDivider: true, timeText: "1分" },
        { title: "构建安装包", statusText: "处理中·执行命令", tone: "running", showDivider: true, timeText: "25分" },
        { title: "检查手环页面", statusText: "等待查看", tone: "waiting", showDivider: false, timeText: "11小时" },
      ],
    });
    const tags = collectTags(frame);

    assert.equal(tags[0], "div");
    assert.equal(tags[1], "swiper");
    assert.equal(tags.filter((tag) => tag === "swiper").length, 1);
    assert.equal(rulesForClass(styles, "task-dot")?.width, "7px");
    assert.equal(rulesForClass(styles, "task-title")?.lines, 2);
    assert.equal(rulesForClass(styles, "task-title")?.textOverflow, "ellipsis");
    assert.equal(rulesForClass(styles, "task-time")?.fontSize, "17px");
  });
});

test("built release package advances over the installed 0.5.1 candidate", async () => {
  const manifest = JSON.parse(await readFile(builtManifestUrl, "utf8"));
  assert.equal(manifest.versionName, "0.5.2");
  assert.equal(
    manifest.versionCode,
    502,
    "the unified release package must upgrade over the installed 0.5.1 candidate",
  );
});

test("built Band 10 pages share one header rhythm without a task title", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const styles = pageExports.default.style;
    const frame = pageExports.default.template({ ...pageExports.default.private });
    const tags = collectTags(frame);

    assert.equal(tags[0], "div");
    assert.equal(tags.filter((tag) => tag === "progress").length, 0);
    assert.equal(rulesForClass(styles, "ring-track"), undefined);
    assert.equal(countClass(frame, "page-title"), 0, "the task page shares the overview header without a title");
    assert.deepEqual(
      {
        syncTop: rulesForClass(styles, "sync-pill")?.top,
        quotaTop: rulesForClass(styles, "quota-section")?.top,
        taskTop: rulesForClass(styles, "task-section")?.top,
      },
      { syncTop: "41px", quotaTop: "110px", taskTop: "115px" },
    );
    assert.deepEqual(
      {
        left: rulesForClass(styles, "sync-pill")?.left,
        width: rulesForClass(styles, "sync-pill")?.width,
        height: rulesForClass(styles, "sync-pill")?.height,
        fontSize: rulesForClass(styles, "status-label")?.fontSize,
      },
      { left: "48px", width: "116px", height: "36px", fontSize: "16px" },
    );
    assert.equal(rulesForClass(styles, "clock-time")?.fontSize, "24px");
    assert.equal(rulesForClass(styles, "page-title"), undefined);
    assert.equal(rulesForClass(styles, "section-label")?.fontSize, "20px");
    assert.equal(rulesForClass(styles, "section-label")?.color, "#f7f8f9");
    assert.equal(rulesForClass(styles, "quota-value")?.flexDirection, "row");
    assert.equal(rulesForClass(styles, "quota-value")?.height, "102px");
    assert.equal(rulesForClass(styles, "quota-number")?.fontSize, "96px");
    assert.equal(rulesForClass(styles, "quota-number-compact")?.fontSize, "80px");
    assert.equal(rulesForClass(styles, "quota-unit")?.fontSize, "34px");
    assert.equal(rulesForClass(styles, "quota-unit")?.color, "#f7f8f9");
    assert.equal(rulesForClass(styles, "quota-unit")?.marginBottom, "8px");
    assert.equal(rulesForClass(styles, "divider")?.height, "1px");
    assert.equal(rulesForClass(styles, "divider")?.top, "280px");
    assert.equal(rulesForClass(styles, "reset-section")?.top, "306px");
    assert.equal(rulesForClass(styles, "reset-number")?.fontSize, "58px");
    assert.equal(rulesForClass(styles, "reset-unit")?.fontSize, "22px");
    assert.equal(rulesForClass(styles, "reset-unit")?.marginBottom, "8px");
    assert.equal(rulesForClass(styles, "reset-expiry")?.fontSize, "20px");
  });
});

test("built Band 10 content groups are explicitly vertical on device", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const styles = pageExports.default.style;

    for (const className of ["quota-section", "reset-section", "task-section"]) {
      assert.equal(
        rulesForClass(styles, className)?.flexDirection,
        "column",
        `${className} must not fall back to Vela's horizontal flex direction`,
      );
    }
  });
});

test("built Band 10 task rows reserve color for a single status dot", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const styles = pageExports.default.style;

    assert.equal(rulesForClass(styles, "task-section")?.top, "115px");
    assert.equal(rulesForClass(styles, "task-item")?.height, "86px");
    assert.equal(rulesForClass(styles, "task-dot")?.width, "7px");
    assert.equal(rulesForClass(styles, "task-dot")?.top, "19px");
    assert.equal(rulesForClass(styles, "task-title")?.fontSize, "20px");
    assert.equal(rulesForClass(styles, "task-status")?.fontSize, "17px");
    assert.equal(rulesForClass(styles, "task-time")?.fontSize, "17px");
    assert.equal(rulesForClass(styles, "task-meta")?.width, "140px");
    assert.equal(rulesForClass(styles, "task-status")?.width, "100px");
    assert.equal(rulesForClass(styles, "task-time")?.width, "40px");
    assert.equal(rulesForClass(styles, "task-status")?.color, "#9da8ac");
    assert.equal(rulesForClass(styles, "task-dot-running")?.backgroundColor, "#59aaf2");
    assert.equal(rulesForClass(styles, "task-dot-waiting")?.backgroundColor, "#67ce91");
  });
});

test("pager leaves the full gesture and settling physics to native Vela swiper", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const frame = pageExports.default.template({ ...pageExports.default.private });
    const swiperOptions = frame.children[0].properties.__opts__;

    assert.equal(swiperOptions.duration, undefined, "Vela should calculate animation duration from finger speed");
    assert.equal(swiperOptions.index, undefined, "script must not force the page index after touchend");
    assert.equal(pageExports.default.onPageTouchStart, undefined);
    assert.equal(pageExports.default.onPageTouchEnd, undefined);
    assert.equal(pageExports.default.onPageChange, undefined);
  });
});
