package com.codex.quota.android.ui

import android.content.Context
import androidx.core.content.edit

class NotificationSettingsStore(context: Context) {
  private val preferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): NotificationSettings {
    val defaults = NotificationSettings.Default
    val timingName = preferences.getString(KEY_TIMING, defaults.timing.name)
    return NotificationSettings(
      timing = ReminderTiming.entries.firstOrNull { it.name == timingName } ?: defaults.timing,
      waitingForReview = true,
      needsAuthorization = true,
      phoneNotifications = preferences.getBoolean(KEY_PHONE_NOTIFICATIONS, defaults.phoneNotifications),
      bandNotifications = preferences.getBoolean(KEY_BAND_NOTIFICATIONS, defaults.bandNotifications),
      hideTaskTitles = preferences.getBoolean(KEY_HIDE_TASK_TITLES, defaults.hideTaskTitles),
    )
  }

  fun save(settings: NotificationSettings) {
    preferences.edit {
      putString(KEY_TIMING, settings.timing.name)
      remove("waiting-for-review")
      remove("needs-authorization")
      putBoolean(KEY_PHONE_NOTIFICATIONS, settings.phoneNotifications)
      putBoolean(KEY_BAND_NOTIFICATIONS, settings.bandNotifications)
      putBoolean(KEY_HIDE_TASK_TITLES, settings.hideTaskTitles)
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "notification-settings"
    const val KEY_TIMING = "timing"
    const val KEY_PHONE_NOTIFICATIONS = "phone-notifications"
    const val KEY_BAND_NOTIFICATIONS = "band-notifications"
    const val KEY_HIDE_TASK_TITLES = "hide-task-titles"
  }
}
