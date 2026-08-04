import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const taskState = require("../src/common/task-state.cjs");

test("band task view uses the confirmed compact relative-time labels", () => {
  const now = new Date("2030-01-04T12:00:00.000Z");
  assert.equal(taskState.relativeTimeText(now.getTime() - 60_000, now), "1分");
  assert.equal(taskState.relativeTimeText(now.getTime() - 25 * 60_000, now), "25分");
  assert.equal(taskState.relativeTimeText(now.getTime() - 11 * 3_600_000, now), "11小时");
  assert.equal(taskState.relativeTimeText(now.getTime() - 3 * 86_400_000, now), "3天");
});

test("band task view shows only task state and time without tool activity", () => {
  const now = new Date("2030-01-04T12:00:00.000Z");
  const view = taskState.createTaskView(
    {
      generatedAtMs: now.getTime(),
      chatGptState: "running",
      tasks: [
        {
          title: "允许写入文件",
          state: "needs_authorization",
          updatedAtMs: now.getTime() - 60_000,
        },
        {
          title: "构建安装包",
          state: "running",
          activity: "executing_command",
          updatedAtMs: now.getTime() - 25 * 60_000,
        },
        {
          title: "检查手环页面",
          state: "waiting_for_review",
          updatedAtMs: now.getTime() - 11 * 3_600_000,
        },
      ],
    },
    now,
  );

  assert.equal(view.summaryText, "3项任务");
  assert.deepEqual(
    view.items.map(({ statusText, tone, timeText }) => ({ statusText, tone, timeText })),
    [
      { statusText: "需要授权", tone: "danger", timeText: "1分" },
      { statusText: "处理中", tone: "running", timeText: "25分" },
      { statusText: "等待查看", tone: "waiting", timeText: "11小时" },
    ],
  );
});

test("band task view puts authorization first and exposes compact status groups for the Band page", () => {
  const now = new Date("2030-01-04T12:00:00.000Z");
  const view = taskState.createTaskView(
    {
      generatedAtMs: now.getTime(),
      chatGptState: "running",
      tasks: [
        { title: "等待验收", state: "waiting_for_review", updatedAtMs: now.getTime() - 12 * 60_000 },
        { title: "构建 RPK", state: "running", updatedAtMs: now.getTime() - 5 * 60_000 },
        { title: "确认写入", state: "needs_authorization", updatedAtMs: now.getTime() - 60_000 },
      ],
    },
    now,
  );

  assert.deepEqual(
    view.items.map(({ title, tone, groupText, groupTone, groupCount, showGroup }) => ({
      title,
      tone,
      groupText,
      groupTone,
      groupCount,
      showGroup,
    })),
    [
      { title: "确认写入", tone: "danger", groupText: "需要授权", groupTone: "danger", groupCount: "1", showGroup: true },
      { title: "构建 RPK", tone: "running", groupText: "处理中", groupTone: "running", groupCount: "1", showGroup: true },
      { title: "等待验收", tone: "waiting", groupText: "等待查看", groupTone: "waiting", groupCount: "1", showGroup: true },
    ],
  );
});

test("band task sanitizer rejects oversized or privacy-expanded payloads", () => {
  assert.throws(
    () =>
      taskState.sanitizeTaskSnapshotForBand({
        generatedAtMs: 1,
        chatGptState: "running",
        tasks: Array.from({ length: 4 }, (_, index) => ({
          title: `任务${index}`,
          state: "running",
          updatedAtMs: 1,
        })),
      }),
    /invalid task list/,
  );
  assert.throws(
    () =>
      taskState.sanitizeTaskSnapshotForBand({
        generatedAtMs: 1,
        chatGptState: "running",
        tasks: [{ title: "任务", state: "running", activity: "raw_command", updatedAtMs: 1 }],
      }),
    /invalid task activity/,
  );
});

test("empty and unavailable task states stay explicit", () => {
  assert.deepEqual(taskState.createTaskView(null), {
    summaryText: "任务状态不可用",
    items: [],
  });
  assert.equal(
    taskState.createTaskView({ generatedAtMs: 1, chatGptState: "running", tasks: [] })
      .summaryText,
    "暂无任务",
  );
});
