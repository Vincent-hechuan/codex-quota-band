package com.codex.quota.android.ui

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.SafeActivity
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import com.codex.quota.android.runtime.BandConnectionCheckResult
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

enum class SemanticTone {
  Healthy,
  Warning,
  Danger,
  Primary,
  Neutral,
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

enum class FiveHourQuotaAvailability {
  Available,
  Pending,
  Missing,
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
  val fiveHourQuota: WeeklyQuota? = null,
  val fiveHourQuotaAvailability: FiveHourQuotaAvailability = FiveHourQuotaAvailability.Missing,
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

/** Sync freshness is shown by the pill; it must not recolor a known quota value. */
fun quotaLevelForDisplay(quota: WeeklyQuota?, syncState: SyncState): QuotaLevel =
  if (quota == null) QuotaLevel.Unavailable else quota.level

fun syncStatusTone(state: SyncState): SemanticTone =
  when (state) {
    SyncState.Synced -> SemanticTone.Healthy
    SyncState.Cached -> SemanticTone.Warning
    SyncState.AwaitingConfirmation -> SemanticTone.Primary
    SyncState.Offline -> SemanticTone.Danger
  }

fun taskElapsedLabel(updatedAtMs: Long, nowMs: Long): String = compactElapsedLabel(updatedAtMs, nowMs)

fun phoneTaskMetadata(task: SyncedTask, nowMs: Long): String {
  val status =
    when (task.state) {
      TaskState.Running -> "处理中"
      TaskState.NeedsAuthorization -> "需要授权"
      TaskState.WaitingForReview -> "等待查看"
    }
  val activity =
    when (task.activity) {
      SafeActivity.ExecutingCommand -> "执行命令"
      SafeActivity.ModifyingFiles -> "修改文件"
      SafeActivity.UsingBrowser -> "使用浏览器"
      null -> null
    }
  return listOfNotNull(status, activity, taskElapsedLabel(task.updatedAtMs, nowMs)).joinToString(" · ")
}

fun resetCountTone(availableCount: Int?): SemanticTone =
  when {
    availableCount == null -> SemanticTone.Neutral
    availableCount == 0 -> SemanticTone.Danger
    else -> SemanticTone.Healthy
  }

fun taskStatusEmphasis(state: TaskState): TaskStatusEmphasis =
  when (state) {
    TaskState.NeedsAuthorization -> TaskStatusEmphasis.Attention
    TaskState.Running, TaskState.WaitingForReview -> TaskStatusEmphasis.Default
  }

fun bandConnectionCheckResultLabel(result: BandConnectionCheckResult): String =
  when (result) {
    BandConnectionCheckResult.Connected -> "手环已连接，已恢复同步"
    BandConnectionCheckResult.NotConnected -> "未检测到手环通信，请确认小米运动健康已连接后重试"
    BandConnectionCheckResult.PermissionDenied -> "未取得手环授权，请确认小米运动健康已连接后重试"
  }

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

fun fiveHourResetLabel(
  quota: WeeklyQuota?,
  availability: FiveHourQuotaAvailability,
  zoneId: ZoneId = ZoneId.systemDefault(),
): String =
  when {
    quota != null && availability == FiveHourQuotaAvailability.Available ->
      TIME_FORMATTER.format(Instant.ofEpochMilli(quota.resetsAtMs).atZone(zoneId)) + "重置"
    availability == FiveHourQuotaAvailability.Pending -> "待同步"
    else -> "暂无数据"
  }

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
    elapsedMinutes < 7 * 24 * 60 -> "${elapsedMinutes / (24 * 60)}天"
    elapsedMinutes < 100 * 7 * 24 * 60 -> "${elapsedMinutes / (7 * 24 * 60)}周"
    else -> "99周+"
  }
}

private val EXPIRY_FORMATTER = DateTimeFormatter.ofPattern("M月d日")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val RESET_CARD_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val RESET_CARD_ZONE = ZoneId.of("Asia/Shanghai")
