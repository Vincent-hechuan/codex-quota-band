package com.codex.quota.android.ui

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

enum class SyncState {
  Synced,
  Cached,
  AwaitingConfirmation,
  Offline,
}

enum class DeviceLinkState {
  Connected,
  Disconnected,
  Unavailable,
}

enum class QuotaLevel {
  Healthy,
  Warning,
  Critical,
  Unavailable,
}

enum class TaskStatusEmphasis {
  Default,
  Attention,
}

enum class ReminderTiming {
  Never,
  Unfocused,
  Always,
}

data class NotificationSettings(
  val timing: ReminderTiming,
  val waitingForReview: Boolean,
  val needsAuthorization: Boolean,
  val phoneNotifications: Boolean,
  val bandNotifications: Boolean,
  val hideTaskTitles: Boolean = false,
) {
  companion object {
    val Default =
      NotificationSettings(
        timing = ReminderTiming.Unfocused,
        waitingForReview = true,
        needsAuthorization = true,
        phoneNotifications = true,
        bandNotifications = true,
        hideTaskTitles = false,
      )
  }
}

data class WeeklyQuota(
  val remainingPercent: Int,
  val resetsAtMs: Long,
) {
  init {
    require(remainingPercent in 0..100) { "remaining percentage must be between 0 and 100" }
    require(resetsAtMs >= 0) { "reset timestamp must be non-negative" }
  }

  val level: QuotaLevel
    get() =
      when {
        remainingPercent < 20 -> QuotaLevel.Critical
        remainingPercent < 50 -> QuotaLevel.Warning
        else -> QuotaLevel.Healthy
      }
}

data class ResetCredit(
  val label: String,
  val expiresAtMs: Long,
  val grantedAtMs: Long? = null,
)

data class DeviceConnections(
  val computer: DeviceLinkState,
  val phone: DeviceLinkState,
  val band: DeviceLinkState,
)

data class AppUiState(
  val syncState: SyncState,
  val lastSyncAtMs: Long?,
  val weeklyQuota: WeeklyQuota?,
  val resetAvailableCount: Int?,
  val resetCredits: List<ResetCredit>,
  val connections: DeviceConnections,
  val chatGptState: ChatGptState,
  val tasks: List<SyncedTask>,
  val usageFreshness: UpstreamFreshnessStatus? = null,
  val resetFreshness: UpstreamFreshnessStatus? = null,
  val lastTransportDataAtMs: Long? = null,
) {
  companion object {
    fun empty() =
      AppUiState(
        syncState = SyncState.Offline,
        lastSyncAtMs = null,
        weeklyQuota = null,
        resetAvailableCount = null,
        resetCredits = emptyList(),
        connections =
          DeviceConnections(
            computer = DeviceLinkState.Disconnected,
            phone = DeviceLinkState.Connected,
            band = DeviceLinkState.Disconnected,
          ),
        chatGptState = ChatGptState.NotRunning,
        tasks = emptyList(),
      )
  }
}

fun syncStatusLabel(
  state: SyncState,
  lastSyncAtMs: Long?,
  nowMs: Long,
  usageFreshness: UpstreamFreshnessStatus? = null,
): String {
  val age = lastSyncAtMs?.let { compactElapsedLabel(it, nowMs) }
  return when (state) {
    SyncState.Synced ->
      if (usageFreshness == UpstreamFreshnessStatus.Current) {
        if (age == null) "已同步" else "已同步 $age"
      } else {
        if (age == null) "已同步" else "已同步 $age"
      }
    SyncState.Cached -> if (age == null) "缓存" else "缓存 $age"
    SyncState.AwaitingConfirmation -> "待同步"
    SyncState.Offline -> if (age == null) "离线" else "离线 $age"
  }
}

fun taskElapsedLabel(updatedAtMs: Long, nowMs: Long): String = compactElapsedLabel(updatedAtMs, nowMs)

fun taskStatusEmphasis(state: TaskState): TaskStatusEmphasis =
  when (state) {
    TaskState.NeedsAuthorization -> TaskStatusEmphasis.Attention
    TaskState.Running, TaskState.WaitingForReview -> TaskStatusEmphasis.Default
  }

fun bandConnectionCheckResultLabel(granted: Boolean): String =
  if (granted) "手环已授权，正在同步" else "未取得手环授权，请保持小米运动健康已连接后重试"

/** Returns the delay until the next whole-minute age label update. */
fun millisecondsUntilNextMinute(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L)

fun resetDateLabel(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
  EXPIRY_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zoneId)) + "到期"

fun resetGrantedAtLabel(epochMs: Long?): String =
  epochMs?.let { "发卡 ${RESET_CARD_FORMATTER.format(Instant.ofEpochMilli(it).atZone(RESET_CARD_ZONE))}" }
    ?: "发卡时间待同步"

fun resetExpiresAtLabel(epochMs: Long): String =
  "到期 ${RESET_CARD_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(RESET_CARD_ZONE))}"

fun weeklyResetDateLabel(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
  EXPIRY_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zoneId)) + "重置"

fun remainingTimeLabel(expiresAtMs: Long, nowMs: Long): String {
  val remainingMs = expiresAtMs - nowMs
  if (remainingMs <= 0) return "已到期"
  val remainingMinutes = ceil(remainingMs / 60_000.0).toLong()
  return when {
    remainingMinutes < 60 -> "剩余 ${remainingMinutes}分"
    remainingMinutes < 24 * 60 -> "剩余 ${ceil(remainingMinutes / 60.0).toLong()}小时"
    else -> "剩余 ${ceil(remainingMinutes / (24.0 * 60)).toLong()}天"
  }
}

private fun compactElapsedLabel(thenMs: Long, nowMs: Long): String {
  val elapsedMinutes = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000
  return when {
    elapsedMinutes < 1 -> "刚刚"
    elapsedMinutes < 60 -> "${elapsedMinutes}分"
    elapsedMinutes < 24 * 60 -> "${elapsedMinutes / 60}小时"
    elapsedMinutes < 100 * 24 * 60 -> "${elapsedMinutes / (24 * 60)}天"
    else -> "99天+"
  }
}

private val EXPIRY_FORMATTER = DateTimeFormatter.ofPattern("M月d日")
private val RESET_CARD_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val RESET_CARD_ZONE = ZoneId.of("Asia/Shanghai")
