function unavailableSnapshot(now) {
  return {
    protocolVersion: 1,
    generatedAt: now.toISOString(),
    sourceStatus: "unavailable",
    limitsCollectedAt: null,
    windows: [],
    resetInventory: {
      status: "unavailable",
      availableCount: null,
      cachedAt: null,
      items: [],
    },
    link: { computer: "online", codex: "unavailable" },
  };
}

function staleSnapshot(snapshot, now) {
  return {
    ...snapshot,
    generatedAt: now.toISOString(),
    sourceStatus: "partial",
    link: { computer: "online", codex: "stale" },
  };
}

export function createSnapshotCache({
  collector,
  clock = () => new Date(),
  initialSnapshot = null,
  onTrustedSnapshot = async () => {},
}) {
  let current = initialSnapshot ?? unavailableSnapshot(clock());
  let lastTrusted = initialSnapshot;
  let paused = false;
  let timer = null;
  let refreshInProgress = null;

  const refresh = async () => {
    if (paused) {
      return current;
    }
    if (refreshInProgress) {
      return refreshInProgress;
    }

    refreshInProgress = (async () => {
      try {
        const snapshot = await collector();
        await onTrustedSnapshot(snapshot);
        lastTrusted = snapshot;
        current = snapshot;
      } catch {
        current = lastTrusted
          ? staleSnapshot(lastTrusted, clock())
          : unavailableSnapshot(clock());
      } finally {
        refreshInProgress = null;
      }
      return current;
    })();
    return refreshInProgress;
  };

  const stopTimer = () => {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  };

  return {
    refresh,
    getSnapshot() {
      return current;
    },
    start(refreshEveryMilliseconds = 5_000) {
      stopTimer();
      void refresh();
      timer = setInterval(() => void refresh(), refreshEveryMilliseconds);
      timer.unref?.();
    },
    pause() {
      paused = true;
      stopTimer();
      const base = current;
      current = {
        ...base,
        generatedAt: clock().toISOString(),
        sourceStatus: "paused",
        link: { computer: "paused", codex: base.link.codex === "ok" ? "ok" : "stale" },
      };
    },
    resume(refreshEveryMilliseconds = 5_000) {
      paused = false;
      this.start(refreshEveryMilliseconds);
    },
    close() {
      stopTimer();
    },
  };
}
