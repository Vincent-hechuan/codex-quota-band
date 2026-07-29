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
      assert.equal(viewModel.taskItems[1].statusText, "处理中·执行命令");
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

test("built page turns an otherwise healthy snapshot into a cached relative status after one minute", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const component = pageExports.default;
    const viewModel = createViewModel(component);
    viewModel.hasSnapshot = true;
    viewModel.snapshotStatusText = "已同步";
    viewModel.snapshotStatusTone = "healthy";
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
    viewModel.lastSyncClockText = "06:02";

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

test("built page shows a fixed-height cached status when Windows is unreachable", async () => {
  await withBuiltPage((pageExports) => {
    pageExports.entry(pageExports);
    const component = pageExports.default;
    const viewModel = createViewModel(component);
    viewModel.requestNonce = "band-test-error";
    viewModel.lastSyncClockText = "06:02";
    viewModel.hasSnapshot = true;
    viewModel.quotaTone = "healthy";

    component.handleMessage.call(viewModel, {
      type: "quota_error",
      nonce: "band-test-error",
      code: "windows_unreachable",
    });

    assert.equal(viewModel.statusText, "离线");
    assert.equal(viewModel.statusTone, "danger");
    assert.equal(viewModel.statusTimeText, "06:02");
    assert.equal(viewModel.quotaTone, "healthy");
    assert.equal(component.template(viewModel).tag, "div");
  });
});
