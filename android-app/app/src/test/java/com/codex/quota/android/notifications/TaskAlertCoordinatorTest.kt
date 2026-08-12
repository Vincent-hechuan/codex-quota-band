package com.codex.quota.android.notifications

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.TaskSnapshot
import com.codex.quota.android.ui.NotificationSettings
import com.codex.quota.android.ui.ReminderTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskAlertCoordinatorTest {
  @Test
  fun runningTaskAlertsPhoneAndBandOnceWhenItStartsWaitingForReview() {
    val phoneAlerts = mutableListOf<SyncedTask>()
    val bandAlerts = mutableListOf<SyncedTask>()
    val coordinator =
      TaskAlertCoordinator(
        phoneDispatcher = phoneAlerts::add,
        bandDispatcher = bandAlerts::add,
      )

    coordinator.ingest(snapshot(TaskState.Running), reconnect = true)
    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = false)
    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = false)

    assertEquals(listOf("手机Codex额度开发"), phoneAlerts.map(SyncedTask::title))
    assertEquals(listOf("手机Codex额度开发"), bandAlerts.map(SyncedTask::title))
  }

  @Test
  fun phoneAndBandDeliverySwitchesAreIndependent() {
    val phoneAlerts = mutableListOf<SyncedTask>()
    val bandAlerts = mutableListOf<SyncedTask>()
    val coordinator =
      TaskAlertCoordinator(
        phoneDispatcher = phoneAlerts::add,
        bandDispatcher = bandAlerts::add,
      )
    coordinator.updateSettings(
      NotificationSettings(
        timing = ReminderTiming.Always,
        waitingForReview = true,
        needsAuthorization = true,
        phoneNotifications = false,
        bandNotifications = true,
      ),
    )

    coordinator.ingest(snapshot(TaskState.Running), reconnect = true)
    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = false)

    assertEquals(emptyList<SyncedTask>(), phoneAlerts)
    assertEquals(1, bandAlerts.size)
  }

  @Test
  fun reconnectDoesNotReplayAnExistingWaitingTask() {
    val alerts = mutableListOf<SyncedTask>()
    val coordinator = TaskAlertCoordinator(phoneDispatcher = alerts::add, bandDispatcher = {})

    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = true)
    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = false)

    assertEquals(emptyList<SyncedTask>(), alerts)
  }

  @Test
  fun reconnectAlertsWhenAnObservedRunningTaskCompletedWhileDisconnected() {
    val alerts = mutableListOf<SyncedTask>()
    val coordinator = TaskAlertCoordinator(phoneDispatcher = alerts::add, bandDispatcher = {})
    coordinator.ingest(snapshot(TaskState.Running), reconnect = false)

    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = true)

    assertEquals(listOf("手机Codex额度开发"), alerts.map(SyncedTask::title))
  }

  @Test
  fun anAllowedTransitionIsDeliveredWithoutASeparatePhoneForegroundGate() {
    val alerts = mutableListOf<SyncedTask>()
    val coordinator = TaskAlertCoordinator(phoneDispatcher = alerts::add, bandDispatcher = {})
    coordinator.ingest(snapshot(TaskState.Running), reconnect = true)

    coordinator.ingest(snapshot(TaskState.WaitingForReview), reconnect = false)

    assertEquals(listOf("手机Codex额度开发"), alerts.map(SyncedTask::title))
  }

  @Test
  fun defaultModeSuppressesAlertsWhileChatGptIsFocused() {
    val alerts = mutableListOf<SyncedTask>()
    val coordinator = TaskAlertCoordinator(phoneDispatcher = alerts::add, bandDispatcher = {})
    coordinator.ingest(snapshot(TaskState.Running), reconnect = true)

    coordinator.ingest(
      snapshot(TaskState.WaitingForReview, chatGptFocused = true),
      reconnect = false,
    )

    assertEquals(emptyList<SyncedTask>(), alerts)
  }

  private fun snapshot(state: TaskState, chatGptFocused: Boolean = false) =
    TaskSnapshot(
      sequence = 1,
      generatedAtMs = 1,
      chatGptState = ChatGptState.Running,
      chatGptFocused = chatGptFocused,
      tasks =
        listOf(
          SyncedTask(
            conversationId = "conversation-1",
            title = "手机Codex额度开发",
            state = state,
            updatedAtMs = 1,
          ),
        ),
    )
}
