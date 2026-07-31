package com.codex.quota.android.runtime

/**
 * Keeps asynchronous Wearable SDK probes ordered. The SDK may complete an older probe after a
 * newer one, so only the newest probe is allowed to change the visible band state.
 */
internal class WearableRefreshState {
  private var generation = 0L

  fun begin(): Long {
    generation += 1
    return generation
  }

  fun isCurrent(refreshGeneration: Long): Boolean = refreshGeneration == generation

  fun invalidate() {
    generation += 1
  }

  fun connectionRetryDelayMs(attempt: Int): Long? = CONNECTION_RETRY_DELAYS_MS.getOrNull(attempt)

  private companion object {
    val CONNECTION_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
  }
}
