package com.example.codexquotabackgroundprobe

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ProbeApplication : Application(), Application.ActivityLifecycleCallbacks {
  @Volatile private var foregroundActivities = 0
  private val notificationId = AtomicInteger(1000)

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(this)
    createChannels()
    getSystemService(NotificationManager::class.java).cancelAll()
    startConnectionLoop()
  }

  private fun startConnectionLoop() {
    Thread(
        {
          val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
          while (true) {
            try {
              Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 5_000)
                socket.soTimeout = 35_000
                preferences
                  .edit()
                  .putBoolean("connected", true)
                  .putLong("reconnects", preferences.getLong("reconnects", 0) + 1)
                  .putString("last_at", Instant.now().toString())
                  .commit()

                BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                  while (true) {
                    val line = reader.readLine() ?: break
                    recordEvent(line.substringBefore('|'))
                  }
                }
              }
            } catch (_: Exception) {
              preferences
                .edit()
                .putBoolean("connected", false)
                .putString("last_at", Instant.now().toString())
                .commit()
            }
            Thread.sleep(2_000)
          }
        },
        "codex-quota-background-probe",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  private fun recordEvent(event: String) {
    val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val key =
      when (event) {
        "running" -> "running"
        "needs_authorization" -> "needs_authorization"
        "waiting_review" -> "waiting_review"
        else -> return
      }

    preferences
      .edit()
      .putLong("total", preferences.getLong("total", 0) + 1)
      .putLong(key, preferences.getLong(key, 0) + 1)
      .putString("last_event", event)
      .putString("last_at", Instant.now().toString())
      .commit()

    if (event != "running") {
      if (foregroundActivities == 0) {
        postNotification(event)
      } else {
        increment("notification_suppressed_foreground")
        preferences
          .edit()
          .putString("last_notification_status", "前台静默：$event")
          .commit()
      }
    }
  }

  private fun createChannels() {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = getSystemService(NotificationManager::class.java)
    val waiting =
      NotificationChannel(
          WAITING_CHANNEL,
          "等待查看",
          NotificationManager.IMPORTANCE_DEFAULT,
        )
        .apply {
          description = "任务已完成或暂停，等待用户查看"
          setSound(null, null)
          enableVibration(false)
          lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
    val authorization =
      NotificationChannel(
          AUTHORIZATION_CHANNEL,
          "需要授权",
          NotificationManager.IMPORTANCE_HIGH,
        )
        .apply {
          description = "任务需要用户授权后才能继续"
          setSound(null, null)
          enableVibration(true)
          vibrationPattern = AUTHORIZATION_VIBRATION
          lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
    manager.createNotificationChannels(listOf(waiting, authorization))
  }

  private fun postNotification(event: String) {
    increment("notification_attempts")
    if (
      Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      increment("notification_skipped_permission")
      getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString("last_notification_status", "缺少通知权限：$event")
        .commit()
      return
    }

    val manager = getSystemService(NotificationManager::class.java)
    val intent = Intent(this, MainActivity::class.java)
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val needsAuthorization = event == "needs_authorization"
    val notification =
      Notification.Builder(
          this,
          if (needsAuthorization) AUTHORIZATION_CHANNEL else WAITING_CHANNEL,
        )
        .setSmallIcon(
          if (needsAuthorization) android.R.drawable.stat_sys_warning
          else android.R.drawable.stat_notify_sync_noanim,
        )
        .setContentTitle(if (needsAuthorization) "需要授权" else "等待查看")
        .setContentText("后台探针收到测试事件")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(false)
        .setWhen(System.currentTimeMillis())
        .apply {
          if (needsAuthorization) {
            setVibrate(AUTHORIZATION_VIBRATION)
          }
        }
        .build()
    try {
      manager.notify(notificationId.incrementAndGet(), notification)
      increment("notification_posted")
      getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString("last_notification_status", "已提交：$event")
        .commit()
    } catch (exception: Exception) {
      increment("notification_failures")
      getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(
          "last_notification_status",
          "发送失败：${exception.javaClass.simpleName}",
        )
        .commit()
    }
  }

  private fun increment(key: String) {
    val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    preferences.edit().putLong(key, preferences.getLong(key, 0) + 1).commit()
  }

  override fun onActivityResumed(activity: Activity) {
    foregroundActivities += 1
    recordForegroundActivities()
  }

  override fun onActivityPaused(activity: Activity) {
    foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
    recordForegroundActivities()
  }

  private fun recordForegroundActivities() {
    getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putInt("foreground_activities", foregroundActivities)
      .commit()
  }

  override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
  override fun onActivityStarted(activity: Activity) = Unit
  override fun onActivityStopped(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
  override fun onActivityDestroyed(activity: Activity) = Unit

  companion object {
    const val PREFERENCES = "background_probe"
    private const val PORT = 17421
    private const val WAITING_CHANNEL = "waiting_review_v2"
    private const val AUTHORIZATION_CHANNEL = "needs_authorization_v2"
    private val AUTHORIZATION_VIBRATION = longArrayOf(0, 250, 150, 450)
  }
}
