import assert from "node:assert/strict";
import test from "node:test";
import { withBuiltPage } from "./support/built-page-harness.js";

function createViewModel(component) {
  const viewModel = { ...component.private };
  for (const [name, value] of Object.entries(component)) {
    if (typeof value === "function") viewModel[name] = value;
  }
  return viewModel;
}

async function withLifecyclePage(t, callback) {
  const reads = [], ready = [], sends = [], writes = [];
  const timers = new Map();
  let timerId = 0;
  for (const name of ["setTimeout", "setInterval"]) {
    t.mock.method(globalThis, name, (fn) => { timers.set(++timerId, fn); return timerId; });
  }
  for (const name of ["clearTimeout", "clearInterval"]) {
    t.mock.method(globalThis, name, (id) => timers.delete(id));
  }
  const connection = { getReadyState: (o) => ready.push(o), send: (o) => sends.push(o) };
  await withBuiltPage((exports) => {
    exports.entry(exports);
    const page = createViewModel(exports.default);
    page.onInit();
    try { callback({ page, reads, ready, sends, writes, timers, connection }); }
    finally { page.onDestroy(); }
  }, { interconnect: { instance: () => connection },
    storage: { get: (o) => reads.push(o), set: (o) => writes.push(o) } });
}

test("late ready and cache callbacks cannot revive a destroyed page", async (t) => {
  await withLifecyclePage(t, ({ page, ready, reads, sends, timers }) => {
    page.onShow();
    page.onHide();
    page.onDestroy();
    const before = JSON.stringify(page);
    ready[0].success({ status: 1 });
    ready[0].fail();
    for (const read of reads) read.fail();
    assert.equal(sends.length, 0);
    assert.equal(timers.size, 0);
    assert.equal(JSON.stringify(page), before);
  });
});

test("a previous visibility cycle cannot change the reopened page", async (t) => {
  await withLifecyclePage(t, ({ page, ready, sends }) => {
    page.onShow(); page.onHide(); page.onShow();
    ready[1].success({ status: 1 });
    ready[0].fail();
    assert.equal(page.connected, true);
    assert.equal(sends.length, 1);
  });
});

test("late send callbacks and replies cannot revive a hidden request", async (t) => {
  await withLifecyclePage(t, ({ page, ready, sends, timers, writes }) => {
    page.onShow(); ready[0].success({ status: 1 });
    const nonce = sends[0].data.nonce;
    page.onHide();
    const before = JSON.stringify(page);
    sends[0].success(); sends[0].fail();
    page.handleMessage({ type: "quota_error", nonce, taskSnapshot: null });
    assert.equal(timers.size, 0);
    assert.equal(writes.length, 0);
    assert.equal(JSON.stringify(page), before);
  });
});

test("late task cache cannot undo a live empty task board", async (t) => {
  await withLifecyclePage(t, ({ page, ready, reads, sends }) => {
    page.onShow(); ready[0].success({ status: 1 });
    page.handleMessage({ type: "quota_error", nonce: sends[0].data.nonce, taskSnapshot: null });
    reads[1].success(JSON.stringify({ generatedAtMs: 1, chatGptState: "running",
      tasks: [{ title: "old", state: "running", updatedAtMs: 1 }] }));
    assert.equal(page.taskSnapshot, null);
    assert.equal(page.hasTaskItems, false);
  });
});

test("clock updates preserve disconnection and authorization failures", async (t) => {
  await withLifecyclePage(t, ({ page, connection }) => {
    page.hasSnapshot = true;
    page.snapshotStatusText = "已同步"; page.snapshotStatusTone = "healthy";
    page.lastSnapshotAtMs = Date.now();
    connection.onclose(); page.updateClock();
    assert.equal(page.statusText, "离线");
    connection.onerror({ code: 1001 }); page.updateClock();
    assert.equal(page.statusText, "需重新授权");
  });
});

test("late quota cache cannot replace a live snapshot or its status", async (t) => {
  await withLifecyclePage(t, ({ page, ready, reads, sends }) => {
    const snapshot = { protocolVersion: 2, generatedAt: new Date().toISOString(),
      sourceStatus: "ok", limitsCollectedAt: null, windows: [],
      resetInventory: { status: "cached", availableCount: 2, cachedAt: null, items: [] },
      link: { computer: "online", codex: "ok" } };
    page.onShow(); ready[0].success({ status: 1 });
    page.handleMessage({ type: "quota_snapshot", nonce: sends[0].data.nonce, snapshot });
    snapshot.resetInventory.availableCount = 1;
    reads[0].success(JSON.stringify(snapshot)); reads[0].fail();
    assert.equal(page.resetCountText, "2");
    assert.equal(page.statusText, "已同步");
  });
});

test("a reply arriving before send success does not leave a timeout", async (t) => {
  await withLifecyclePage(t, ({ page, ready, sends, timers }) => {
    page.onShow(); ready[0].success({ status: 1 });
    page.handleMessage({ type: "quota_error", nonce: sends[0].data.nonce });
    const timerCount = timers.size;
    sends[0].success();
    assert.equal(timers.size, timerCount);
  });
});

test("a slow phone reply is accepted after timeout until a new request replaces it", async (t) => {
  await withLifecyclePage(t, ({ page, ready, sends, timers }) => {
    page.onShow(); ready[0].success({ status: 1 });
    const nonce = sends[0].data.nonce;
    sends[0].success();
    const [timerId, timeout] = [...timers.entries()].at(-1);
    timers.delete(timerId); timeout();
    assert.equal(page.statusText, "离线");
    page.handleMessage({ type: "quota_snapshot", nonce, snapshot: {
      protocolVersion: 2, generatedAt: new Date().toISOString(), sourceStatus: "ok",
      limitsCollectedAt: null, windows: [],
      resetInventory: { status: "cached", availableCount: 2, cachedAt: null, items: [] },
      link: { computer: "online", codex: "ok" },
    } });
    assert.equal(page.statusText, "已同步");
    assert.equal(page.resetCountText, "2");
  });
});

test("built Band page registers and renders its first frame", async () => {
  await withBuiltPage((pageExports) => {
    assert.equal(typeof pageExports.entry, "function");
    pageExports.entry(pageExports);
    assert.equal(typeof pageExports.default?.template, "function");
    assert.ok(Array.isArray(pageExports.default?.style));

    const firstFrame = pageExports.default.template({
      ...pageExports.default.private,
    });
    assert.equal(firstFrame.tag, "div");
  });
});

test("first frame does not wait for storage or phone callbacks", async () => {
  const storageRequests = [];
  const connection = {};

  await withBuiltPage(
    (pageExports) => {
      pageExports.entry(pageExports);
      const component = pageExports.default;
      const viewModel = createViewModel(component);

      component.onInit.call(viewModel);

      assert.equal(storageRequests.length, 2);
      assert.equal(viewModel.statusText, "读取中");
      assert.equal(viewModel.statusTimeText, "");
      assert.match(viewModel.clockTimeText, /^\d{2}:\d{2}$/);
      assert.equal(component.template(viewModel).tag, "div");
    },
    {
      interconnect: { instance: () => connection },
      storage: {
        get(options) {
          storageRequests.push(options);
        },
        set() {},
      },
    },
  );
});

test("built page applies the privacy-minimized task snapshot beside quota", async () => {
  const storageWrites = [];

  await withBuiltPage(
    (pageExports) => {
      pageExports.entry(pageExports);
      const component = pageExports.default;
      const viewModel = createViewModel(component);
      viewModel.requestNonce = "band-task-test";
      viewModel.refreshing = true;

      component.handleMessage.call(viewModel, {
        type: "quota_error",
        nonce: "band-task-test",
        code: "quota_unavailable",
        taskSnapshot: {
          generatedAtMs: Date.now(),
          chatGptState: "running",
          tasks: [
            {
              title: "构建安装包",
              state: "running",
              activity: "executing_command",
              updatedAtMs: Date.now() - 60_000,
            },
            {
              title: "允许写入",
              state: "needs_authorization",
              updatedAtMs: Date.now() - 120_000,
            },
          ],
        },
      });

      assert.equal(viewModel.taskSummaryText, "2项任务");
      assert.equal(viewModel.hasTaskItems, true);
      assert.equal(viewModel.taskItems[0].statusText, "需要授权");
      assert.equal(viewModel.taskItems[0].groupText, "需要授权");
      assert.equal(viewModel.taskItems[1].statusText, "处理中");
      assert.equal(viewModel.taskItems[1].groupText, "处理中");
      assert.equal(storageWrites.length, 1);
      assert.equal(component.template(viewModel).tag, "div");
    },
    {
      storage: {
        get() {},
        set(options) {
          storageWrites.push(options);
        },
      },
    },
  );
});

test("built page applies Snapshot v1 to the visible weekly quota and reset inventory", async () => {
  const storageWrites = [];

  await withBuiltPage(
    (pageExports) => {
      pageExports.entry(pageExports);
      const component = pageExports.default;
      const viewModel = createViewModel(component);
      viewModel.requestNonce = "band-test-1";
      viewModel.refreshing = true;

      component.handleMessage.call(viewModel, {
        type: "quota_snapshot",
        nonce: "band-test-1",
        snapshot: {
          protocolVersion: 1,
          generatedAt: "2030-01-01T00:00:00.000Z",
          sourceStatus: "ok",
          limitsCollectedAt: "2030-01-01T00:00:00.000Z",
          windows: [
            {
              id: "codex:primary:300",
              name: "five_hour",
              windowMinutes: 300,
              remainingPercent: 68,
              resetsAt: "2030-01-01T05:00:00.000Z",
              status: "current",
            },
            {
              id: "codex:weekly",
              name: "weekly",
              windowMinutes: 10080,
              remainingPercent: 1,
              resetsAt: "2030-01-08T00:00:00.000Z",
              status: "current",
            },
          ],
          resetInventory: {
            status: "cached",
            availableCount: 2,
            cachedAt: "2030-01-01T00:00:00.000Z",
            items: [
              {
                id: "reset-1",
                title: "Full reset",
                status: "available",
                expiresAt: "2030-01-04T00:00:00.000Z",
              },
              {
                id: "reset-2",
                title: "Full reset",
                status: "available",
                expiresAt: "2030-01-05T00:00:00.000Z",
              },
            ],
          },
          link: { computer: "online", codex: "ok" },
        },
      });

      assert.equal(viewModel.statusText, "已同步");
      assert.equal(viewModel.statusTone, "healthy");
      assert.equal(viewModel.statusTimeText, "", "fresh snapshots do not repeat a sync clock");
      assert.equal(viewModel.resetCountText, "2");
      assert.equal(viewModel.resetExpiryText, "1月4日到期");
      assert.equal(viewModel.quotaRemainingText, "1%");
      assert.equal(viewModel.quotaResetText, "1月8日重置");
      assert.equal(viewModel.quotaTone, "danger");
      assert.equal(viewModel.fiveHourNumberText, "68");
      assert.equal(viewModel.fiveHourUnitText, "%");
      assert.equal(viewModel.fiveHourTone, "healthy");
      assert.equal(viewModel.weeklyProgressPercent, 1);
      assert.equal(storageWrites.length, 1);
      assert.equal(component.template(viewModel).tag, "div");
    },
    {
      storage: {
        get() {},
        set(options) {
          storageWrites.push(options);
        },
      },
    },
  );
});

test("built page tolerates two refresh intervals before showing cached quota", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const component = pageExports.default;
    const viewModel = createViewModel(component);
    viewModel.hasSnapshot = true;
    viewModel.snapshotStatusText = "已同步";
    viewModel.snapshotStatusTone = "healthy";
    viewModel.lastSnapshotAtMs = Date.now() - 119_999;

    component.updateSyncFreshness.call(viewModel);

    assert.equal(viewModel.statusText, "已同步");
    assert.equal(viewModel.statusTone, "healthy");

    viewModel.lastSnapshotAtMs = Date.now() - 121_000;

    component.updateSyncFreshness.call(viewModel);

    assert.equal(viewModel.statusText, "缓存");
    assert.equal(viewModel.statusTone, "warning");
    assert.equal(viewModel.statusTimeText, "2分");
  });
});

test("built page uses relative minutes for a locally restored cached snapshot", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const component = pageExports.default;
    const viewModel = createViewModel(component);
    viewModel.lastSnapshotAtMs = Date.now() - 121_000;
    component.showCachedStatus.call(viewModel, "显示缓存", "warning");

    assert.equal(viewModel.statusText, "缓存");
    assert.equal(viewModel.statusTone, "warning");
    assert.equal(viewModel.statusTimeText, "2分");
  });
});

test("wearable channel authorization errors direct users to reauthorize in Codex额度", async () => {
  const connection = {};

  await withBuiltPage(
    (pageExports) => {
      pageExports.entry(pageExports);
      const component = pageExports.default;
      const viewModel = createViewModel(component);

      component.onInit.call(viewModel);
      connection.onerror({ code: 1001 });

      assert.equal(viewModel.statusText, "需重新授权");
    },
    {
      interconnect: { instance: () => connection },
      storage: { get() {}, set() {} },
    },
  );
});

test("built page shows relative offline age instead of a last-sync clock when Windows is unreachable", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const component = pageExports.default;
    const viewModel = createViewModel(component);
    viewModel.requestNonce = "band-test-error";
    viewModel.lastSnapshotAtMs = Date.now() - 15 * 60 * 60_000;
    viewModel.hasSnapshot = true;
    viewModel.quotaTone = "healthy";

    component.handleMessage.call(viewModel, {
      type: "quota_error",
      nonce: "band-test-error",
      code: "windows_unreachable",
    });

    assert.equal(viewModel.statusText, "离线");
    assert.equal(viewModel.statusTone, "danger");
    assert.equal(viewModel.statusTimeText, "15小时");
    assert.equal(viewModel.quotaTone, "healthy");
    assert.equal(component.template(viewModel).tag, "div");
  });
});
