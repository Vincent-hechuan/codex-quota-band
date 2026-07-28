const PROBE_DELAY_SECONDS = 60;

function createClosedAppProbe() {
  return {
    message: {
      contentTitle: "Codex 提醒测试",
      contentText: "60 秒测试提醒：请确认手环是否显示并震动。",
      clickAction: { uri: "/pages/index" },
    },
    buildInfo: {
      latencyTime: PROBE_DELAY_SECONDS,
      isPersisted: false,
    },
  };
}

function createImmediateProbe() {
  return {
    contentTitle: "Codex 提醒测试",
    contentText: "前台通知测试：请确认手环是否显示并震动。",
    clickAction: { uri: "/pages/index" },
  };
}

module.exports = { createClosedAppProbe, createImmediateProbe };
