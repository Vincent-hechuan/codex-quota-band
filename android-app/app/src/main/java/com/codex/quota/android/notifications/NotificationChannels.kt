package com.codex.quota.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

enum class ChannelImportance {
  Low,
  Default,
  High,
}

data class NotificationChannelSpec(
  val id: String,
  val name: String,
  val description: String,
  val importance: ChannelImportance,
  val vibrate: Boolean,
)

object NotificationChannels {
  val specs =
    listOf(
      NotificationChannelSpec(
        id = NEEDS_AUTHORIZATION_CHANNEL_ID,
        name = "需要授权",
        description = "ChatGPT Windows 任务需要你处理授权时提醒",
        importance = ChannelImportance.High,
        vibrate = true,
      ),
      NotificationChannelSpec(
        id = WAITING_FOR_REVIEW_CHANNEL_ID,
        name = "等待查看",
        description = "ChatGPT Windows 任务停止并等待查看时振动提醒",
        importance = ChannelImportance.High,
        vibrate = true,
      ),
    )

  fun create(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    specs.forEach { spec ->
      val importance =
        when (spec.importance) {
          ChannelImportance.Low -> NotificationManager.IMPORTANCE_LOW
          ChannelImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
          ChannelImportance.High -> NotificationManager.IMPORTANCE_HIGH
        }
      val channel =
        NotificationChannel(spec.id, spec.name, importance).apply {
          description = spec.description
          setSound(null, null)
          enableVibration(spec.vibrate)
        }
      manager.createNotificationChannel(channel)
    }
    LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
  }

  const val NEEDS_AUTHORIZATION_CHANNEL_ID = "needs-authorization-v4"
  const val WAITING_FOR_REVIEW_CHANNEL_ID = "waiting-for-review-v4"

  private val LEGACY_CHANNEL_IDS =
    listOf(
      "needs-authorization",
      "waiting-for-review",
      "needs-authorization-v2",
      "waiting-for-review-v2",
      "needs-authorization-v3",
      "waiting-for-review-v3",
    )
}
