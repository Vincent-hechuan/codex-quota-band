package com.codex.quota.android.notifications

import android.app.Activity
import android.os.Bundle
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState

class DebugNotificationActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val state =
      when (intent.getStringExtra(EXTRA_STATE)) {
        "needs_authorization" -> TaskState.NeedsAuthorization
        "waiting_for_review" -> TaskState.WaitingForReview
        else -> {
          finish()
          return
        }
      }
    TaskNotificationDispatcher(applicationContext)
      .notify(
        SyncedTask(
          conversationId = "debug-${state.name}",
          title = "通知链路测试",
          state = state,
          updatedAtMs = System.currentTimeMillis(),
        ),
      )
    finish()
  }

  private companion object {
    const val EXTRA_STATE = "state"
  }
}
