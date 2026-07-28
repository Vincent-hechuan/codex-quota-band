import assert from "node:assert/strict";
import test from "node:test";
import plan from "../src/common/notification-plan.cjs";

test("creates a one-minute closed-app notification probe with no quota or task data", () => {
  const request = plan.createClosedAppProbe();

  assert.deepEqual(request, {
    message: {
      contentTitle: "Codex 提醒测试",
      contentText: "60 秒测试提醒：请确认手环是否显示并震动。",
      clickAction: { uri: "/pages/index" },
    },
    buildInfo: {
      latencyTime: 60,
      isPersisted: false,
    },
  });
});

test("creates a separate immediate notification probe for the foreground diagnostic", () => {
  assert.deepEqual(plan.createImmediateProbe(), {
    contentTitle: "Codex 提醒测试",
    contentText: "前台通知测试：请确认手环是否显示并震动。",
    clickAction: { uri: "/pages/index" },
  });
});
