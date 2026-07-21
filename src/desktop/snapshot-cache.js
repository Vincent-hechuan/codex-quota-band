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

function retainResetInventory(snapshot, previous, now) {
  const currentStatus = snapshot?.resetInventory?.status;
  const previousStatus = previous?.resetInventory?.status;
  const canRetain =
    snapshot?.sourceStatus === "partial" &&
    ["missing", "unavailable"].includes(currentStatus) &&
    ["cached", "cached_derived"].includes(previousStatus) &&
    Number.isInteger(previous.resetInventory.availableCount);

  if (!canRetain) return snapshot;

  const previousItems = Array.isArray(previous.resetInventory.items)
    ? previous.resetInventory.items
    : [];
  const items = previousItems.filter(
    (item) =>
      item?.status === "available" &&
      typeof item.expiresAt === "string" &&
      Date.parse(item.expiresAt) > now.getTime(),
  );
  if (items.length === 0 && previous.resetInventory.availableCount !== 0) {
    return snapshot;
  }

  const derived =
    previousStatus === "cached_derived" ||
    items.length !== previousItems.length ||
    items.length !== previous.resetInventory.availableCount;
  return {
    ...snapshot,
    resetInventory: {
      ...previous.resetInventory,
      status: derived ? "cached_derived" : "cached",
      availableCount: items.length,
      items,
    },
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
        const snapshot = retainResetInventory(await collector(), lastTrusted, clock());
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
