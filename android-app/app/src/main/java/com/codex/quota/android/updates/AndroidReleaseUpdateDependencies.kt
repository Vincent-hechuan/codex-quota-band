package com.codex.quota.android.updates

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubReleaseTransport(
  currentVersion: String,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .callTimeout(8, TimeUnit.SECONDS)
      .build(),
) : ReleaseTransport {
  private val userAgent = "CodexQuota/$currentVersion"

  override fun fetchLatestRelease(): String {
    val request =
      Request.Builder()
        .url(LATEST_RELEASE_API_URL)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", userAgent)
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("GitHub release request failed")
      response.body.string()
    }
  }
}

class SharedPreferencesUpdateCheckSchedule(context: Context) : UpdateCheckSchedule {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun lastAutomaticAttemptMs(): Long = preferences.getLong(KEY_LAST_AUTOMATIC_ATTEMPT, 0L)

  override fun recordAutomaticAttempt(nowMs: Long) {
    preferences.edit { putLong(KEY_LAST_AUTOMATIC_ATTEMPT, nowMs) }
  }

  private companion object {
    const val PREFERENCES_NAME = "release-update-check"
    const val KEY_LAST_AUTOMATIC_ATTEMPT = "last-automatic-attempt-ms"
  }
}

private const val LATEST_RELEASE_API_URL =
  "https://api.github.com/repos/Vincent-hechuan/codex-quota-band/releases/latest"
