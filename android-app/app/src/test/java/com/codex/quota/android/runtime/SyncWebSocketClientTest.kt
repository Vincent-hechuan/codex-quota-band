package com.codex.quota.android.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWebSocketClientTest {
  @Test
  fun reconnectBackoffIsBounded() {
    assertEquals(1_000L, SyncWebSocketClient.reconnectDelayMs(0))
    assertEquals(2_000L, SyncWebSocketClient.reconnectDelayMs(1))
    assertEquals(5_000L, SyncWebSocketClient.reconnectDelayMs(2))
    assertEquals(10_000L, SyncWebSocketClient.reconnectDelayMs(3))
    assertEquals(30_000L, SyncWebSocketClient.reconnectDelayMs(99))
  }

  @Test
  fun manual_refresh_is_ignored_before_the_phone_has_a_paired_connection() {
    val client = SyncWebSocketClient(CoroutineScope(SupervisorJob()), RuntimeStateRepository())

    assertFalse(client.refresh())
  }

  @Test
  fun automaticRefreshRunsBeforeTheOneMinuteFreshnessDeadline() {
    assertTrue(SyncWebSocketClient.automaticRefreshIntervalMs() < 60_000L)
  }

  @Test
  fun automaticRefreshRetriesOnTheNextWatchdogTickUntilTheRequestIsActuallySent() {
    val schedule = AutomaticRefreshSchedule()

    repeat(8) { assertFalse(schedule.onWatchdogTick { false }) }
    assertTrue(schedule.onWatchdogTick { false })
    assertTrue(schedule.onWatchdogTick { true })
    repeat(8) { assertFalse(schedule.onWatchdogTick { false }) }
    assertTrue(schedule.onWatchdogTick { true })
  }
}
