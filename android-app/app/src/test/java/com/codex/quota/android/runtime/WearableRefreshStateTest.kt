package com.codex.quota.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableRefreshStateTest {
  @Test
  fun lateResultFromAnEarlierRefreshCannotReplaceTheLatestBandState() {
    val state = WearableRefreshState()

    val firstRefresh = state.begin()
    val latestRefresh = state.begin()

    assertFalse(state.isCurrent(firstRefresh))
    assertTrue(state.isCurrent(latestRefresh))
  }

  @Test
  fun connectedWearableGetsOnlyBoundedShortRecoveryProbes() {
    val state = WearableRefreshState()

    assertEquals(1_000L, state.connectionRetryDelayMs(0))
    assertEquals(2_000L, state.connectionRetryDelayMs(1))
    assertEquals(4_000L, state.connectionRetryDelayMs(2))
    assertNull(state.connectionRetryDelayMs(3))
  }

  @Test
  fun stoppingTheBridgeInvalidatesAnyDelayedRecoveryProbe() {
    val state = WearableRefreshState()
    val refresh = state.begin()

    state.invalidate()

    assertFalse(state.isCurrent(refresh))
  }

}
