package com.codex.quota.android.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.codex.quota.android.MainActivity
import com.codex.quota.android.R
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState

data class TaskNotificationContent(
  val channelId: String,
  val title: String,
  val body: String,
) {
  companion object {
    fun from(task: SyncedTask): TaskNotificationContent? =
      when (task.state) {
        TaskState.Running -> null
        TaskState.NeedsAuthorization ->
          TaskNotificationContent(
            channelId = NotificationChannels.NEEDS_AUTHORIZATION_CHANNEL_ID,
            title = "需要授权",
            body = task.title,
          )
        TaskState.WaitingForReview ->
          TaskNotificationContent(
            channelId = NotificationChannels.WAITING_FOR_REVIEW_CHANNEL_ID,
            title = "等待查看",
            body = task.title,
          )
      }
  }
}

class TaskNotificationDispatcher(
  private val context: Context,
) {
  fun notify(task: SyncedTask): Boolean {
    val content = TaskNotificationContent.from(task) ?: return false
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return false
    }
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false

    val openApp =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        context,
        task.conversationId.hashCode(),
        openApp,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(context, content.channelId)
        .setSmallIcon(R.drawable.codex_quota_notification)
        .setContentTitle(content.title)
        .setContentText(content.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setLocalOnly(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    manager.notify(task.conversationId.hashCode(), notification)
    return true
  }
}
