package com.codex.quota.android.notifications

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskNotificationDispatcherTest {
  @Test
  fun mapsOnlyTheTwoAllowedAlertStatesToMinimizedContent() {
    val authorization = TaskNotificationContent.from(task(TaskState.NeedsAuthorization))
    val waiting = TaskNotificationContent.from(task(TaskState.WaitingForReview))

    assertEquals("需要授权", authorization?.title)
    assertEquals("本地任务", authorization?.body)
    assertEquals(NotificationChannels.NEEDS_AUTHORIZATION_CHANNEL_ID, authorization?.channelId)
    assertEquals("等待查看", waiting?.title)
    assertEquals(NotificationChannels.WAITING_FOR_REVIEW_CHANNEL_ID, waiting?.channelId)
  }

  @Test
  fun runningTasksNeverCreateNotifications() {
    assertNull(TaskNotificationContent.from(task(TaskState.Running)))
  }

  private fun task(state: TaskState) =
    SyncedTask(
      conversationId = "conversation-1",
      title = "本地任务",
      state = state,
      updatedAtMs = 1,
    )
}
