package com.example.codexquotabackgroundprobe

import android.content.Context

data class ProbeSnapshot(
  val connected: Boolean,
  val total: Long,
  val running: Long,
  val needsAuthorization: Long,
  val waitingReview: Long,
  val reconnects: Long,
  val notificationAttempts: Long,
  val notificationPosted: Long,
  val notificationFailures: Long,
  val notificationSkippedPermission: Long,
  val notificationSuppressedForeground: Long,
  val foregroundActivities: Int,
  val lastNotificationStatus: String,
  val lastEvent: String,
  val lastAt: String,
) {
  companion object {
    fun read(context: Context): ProbeSnapshot {
      val preferences =
        context.getSharedPreferences(ProbeApplication.PREFERENCES, Context.MODE_PRIVATE)
      return ProbeSnapshot(
        connected = preferences.getBoolean("connected", false),
        total = preferences.getLong("total", 0),
        running = preferences.getLong("running", 0),
        needsAuthorization = preferences.getLong("needs_authorization", 0),
        waitingReview = preferences.getLong("waiting_review", 0),
        reconnects = preferences.getLong("reconnects", 0),
        notificationAttempts = preferences.getLong("notification_attempts", 0),
        notificationPosted = preferences.getLong("notification_posted", 0),
        notificationFailures = preferences.getLong("notification_failures", 0),
        notificationSkippedPermission =
          preferences.getLong("notification_skipped_permission", 0),
        notificationSuppressedForeground =
          preferences.getLong("notification_suppressed_foreground", 0),
        foregroundActivities = preferences.getInt("foreground_activities", 0),
        lastNotificationStatus =
          preferences.getString("last_notification_status", "暂无") ?: "暂无",
        lastEvent = preferences.getString("last_event", "暂无") ?: "暂无",
        lastAt = preferences.getString("last_at", "暂无") ?: "暂无",
      )
    }
  }
}
