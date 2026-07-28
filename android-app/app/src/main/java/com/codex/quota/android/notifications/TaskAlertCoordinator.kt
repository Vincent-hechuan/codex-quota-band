package com.codex.quota.android.notifications

import com.codex.quota.android.domain.AlertContext
import com.codex.quota.android.domain.NotificationMode
import com.codex.quota.android.domain.NotificationPolicy
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.TaskSnapshot
import com.codex.quota.android.ui.NotificationSettings
import com.codex.quota.android.ui.ReminderTiming

class TaskAlertCoordinator(
  private val phoneDispatcher: (SyncedTask) -> Unit,
  private val bandDispatcher: (SyncedTask) -> Unit,
) {
  private val lock = Any()
  private val previousStates = mutableMapOf<String, TaskState>()
  private var androidForeground = false
  private var policy = NotificationPolicy.default()

  fun ingest(snapshot: TaskSnapshot, reconnect: Boolean) {
    synchronized(lock) {
      snapshot.tasks.forEach { task ->
        val previousState = previousStates[task.conversationId]
        previousStates[task.conversationId] = task.state
        if (previousState == task.state) return@forEach

        val delivery =
          policy.plan(
            task.state,
            AlertContext(
              chatGptFocused = snapshot.chatGptFocused,
              androidForeground = androidForeground,
              reconnect = reconnect && previousState == null,
            ),
          )
        if (delivery.phone) phoneDispatcher(task)
        if (delivery.band) bandDispatcher(task)
      }
    }
  }

  fun setAndroidForeground(foreground: Boolean) {
    synchronized(lock) { androidForeground = foreground }
  }

  fun updateSettings(settings: NotificationSettings) {
    synchronized(lock) {
      policy =
        NotificationPolicy.create(
          mode =
            when (settings.timing) {
              ReminderTiming.Never -> NotificationMode.Never
              ReminderTiming.Unfocused -> NotificationMode.WhenChatGptUnfocused
              ReminderTiming.Always -> NotificationMode.Always
            },
          waitingForReview = settings.waitingForReview,
          needsAuthorization = settings.needsAuthorization,
          phone = settings.phoneNotifications,
          band = settings.bandNotifications,
        )
    }
  }
}
