function windowLabel(window) {
  if (window.name === "weekly" || window.windowMinutes === 10_080) {
    return "周额度";
  }
  if (window.name === "five_hour" || window.windowMinutes === 300) {
    return "5 小时额度";
  }
  return Number.isInteger(window.windowMinutes)
    ? `${window.windowMinutes} 分钟额度`
    : "额度";
}

export function snapshotSummary(snapshot) {
  const mainWindow = snapshot.windows.find((window) => window.status === "current");
  const quota = Number.isInteger(mainWindow?.remainingPercent)
    ? `${windowLabel(mainWindow)} ${mainWindow.remainingPercent}%`
    : "额度未知";
  const resets = Number.isInteger(snapshot.resetInventory.availableCount)
    ? `重置 ${snapshot.resetInventory.availableCount} 次`
    : "重置次数未知";
  const state = snapshot.sourceStatus === "ok" ? "" : ` · ${snapshot.sourceStatus}`;
  return `${quota} · ${resets}${state}`;
}
