package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.CodexLinkStatus
import com.codex.quota.android.protocol.ComputerLinkStatus
import com.codex.quota.android.protocol.QuotaSnapshot
import com.codex.quota.android.protocol.QuotaSourceStatus
import com.codex.quota.android.protocol.QuotaWindowStatus
import com.codex.quota.android.protocol.QuotaWireContract
import com.codex.quota.android.protocol.ResetInventorySnapshot
import com.codex.quota.android.protocol.ResetInventoryStatus
import com.codex.quota.android.protocol.TaskSnapshot
import com.codex.quota.android.protocol.TaskWireContract
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import com.codex.quota.android.ui.AppUiState
import com.codex.quota.android.ui.DeviceConnections
import com.codex.quota.android.ui.DeviceLinkState
import com.codex.quota.android.ui.FiveHourQuotaAvailability
import com.codex.quota.android.ui.ResetCredit
import com.codex.quota.android.ui.SyncState
import com.codex.quota.android.ui.WeeklyQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IngestResult {
  Accepted,
  IgnoredStale,
}

class RuntimeStateRepository(
  private val taskVisibility: TaskVisibilityStore = InMemoryTaskVisibilityStore(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val mutableState = MutableStateFlow(AppUiState.empty())
  private var quotaSnapshot: QuotaSnapshot? = null
  private var trustedResetInventory: ResetInventorySnapshot? = null
  private var sourceTaskSnapshot: TaskSnapshot? = null
  private var taskSnapshot: TaskSnapshot? = null
  private var transportConnected = false
  private var bandConnected = false
  private var taskSequenceInConnection: Long? = null
  private var lastTransportDataAtMs: Long? = null

  val state: StateFlow<AppUiState> = mutableState.asStateFlow()

  fun ingestQuota(payload: String): IngestResult = ingestQuota(QuotaWireContract.decode(payload))

  fun ingestTasks(payload: String): IngestResult = ingestTasks(TaskWireContract.decode(payload))

  fun markTransportConnected() {
    synchronized(lock) {
      if (!transportConnected) taskSequenceInConnection = null
      transportConnected = true
      publish()
    }
  }

  fun markTransportDisconnected() {
    synchronized(lock) {
      transportConnected = false
      taskSequenceInConnection = null
      publish()
    }
  }

  fun markTransportDataReceived() {
    synchronized(lock) {
      if (!transportConnected) taskSequenceInConnection = null
      transportConnected = true
      lastTransportDataAtMs = clock()
      publish()
    }
  }

  /** Re-evaluates the age of the last upstream quota confirmation without changing cached data. */
  fun reassessQuotaFreshness() {
    synchronized(lock) { publish() }
  }

  fun setBandConnected(connected: Boolean) {
    synchronized(lock) {
      bandConnected = connected
      publish()
    }
  }

  fun latestQuotaSnapshot(): QuotaSnapshot? = synchronized(lock) { quotaSnapshot }

  fun latestTaskSnapshot(): TaskSnapshot? = synchronized(lock) { taskSnapshot }

  /** Removes a task from this phone and its derived band summary, never from ChatGPT itself. */
  fun removeTaskFromBoard(conversationId: String) {
    synchronized(lock) {
      val task = taskSnapshot?.tasks?.firstOrNull { it.conversationId == conversationId } ?: return
      taskVisibility.hide(task)
      taskSnapshot = sourceTaskSnapshot?.visibleTasks()
      publish()
    }
  }

  internal fun ingestQuota(snapshot: QuotaSnapshot): IngestResult =
    synchronized(lock) {
      val previous = quotaSnapshot
      if (previous != null && snapshot.generatedAtMs <= previous.generatedAtMs) {
        return@synchronized IngestResult.IgnoredStale
      }
      ensureConnectedForIncomingData()
      lastTransportDataAtMs = clock()
      quotaSnapshot = snapshot
      if (snapshot.resetInventory.status.isTrusted()) {
        trustedResetInventory = snapshot.resetInventory
      } else if (trustedResetInventory?.hasUsableData(clock()) != true) {
        trustedResetInventory = null
      }
      publish()
      IngestResult.Accepted
    }

  internal fun ingestTasks(snapshot: TaskSnapshot): IngestResult =
    synchronized(lock) {
      val previous = sourceTaskSnapshot
      if (previous != null && snapshot.generatedAtMs < previous.generatedAtMs) {
        return@synchronized IngestResult.IgnoredStale
      }
      val previousSequence = taskSequenceInConnection
      if (previousSequence != null && snapshot.sequence <= previousSequence) {
        return@synchronized IngestResult.IgnoredStale
      }
      if (previous != null && previous == snapshot) {
        return@synchronized IngestResult.IgnoredStale
      }
      ensureConnectedForIncomingData()
      lastTransportDataAtMs = clock()
      sourceTaskSnapshot = snapshot
      taskSnapshot = snapshot.visibleTasks()
      taskSequenceInConnection = snapshot.sequence
      publish()
      IngestResult.Accepted
    }

  private fun ensureConnectedForIncomingData() {
    if (!transportConnected) taskSequenceInConnection = null
    transportConnected = true
  }

  private fun TaskSnapshot.visibleTasks(): TaskSnapshot =
    copy(tasks = tasks.filterNot(taskVisibility::isHidden))

  private fun publish() {
    val nowMs = clock()
    val quota = quotaSnapshot
    val computerOnline =
      transportConnected && quota?.computerLink != ComputerLinkStatus.Offline
    val effectiveResetInventory =
      when {
        quota?.resetInventory?.status?.isTrusted() == true &&
          quota.resetInventory.hasUsableData(nowMs) -> quota.resetInventory
        trustedResetInventory?.hasUsableData(nowMs) == true -> trustedResetInventory
        else -> null
      }
    val usageFreshness = quota?.upstreamFreshness?.usage
    val lastQuotaConfirmedAt =
      usageFreshness?.lastSuccessAtMs
        ?: quota?.takeIf { it.upstreamFreshness == null }?.generatedAtMs

    mutableState.value =
      AppUiState(
        syncState = syncState(quota, computerOnline, nowMs),
        lastSyncAtMs = lastQuotaConfirmedAt,
        weeklyQuota = quota?.weeklyQuota(nowMs),
        resetAvailableCount = effectiveResetInventory?.availableCount,
        resetCredits =
          effectiveResetInventory
            ?.items
            ?.asSequence()
            ?.filter { it.expiresAtMs > nowMs }
            ?.sortedBy { it.expiresAtMs }
            ?.mapIndexed { index, item ->
              ResetCredit(
                label = item.title.ifBlank { "重置卡 ${index + 1}" },
                expiresAtMs = item.expiresAtMs,
                grantedAtMs = item.grantedAtMs,
              )
            }
            ?.toList()
            .orEmpty(),
        connections =
          DeviceConnections(
            computer =
              if (computerOnline) DeviceLinkState.Connected else DeviceLinkState.Disconnected,
            phone = DeviceLinkState.Connected,
            band = if (bandConnected) DeviceLinkState.Connected else DeviceLinkState.Disconnected,
          ),
        chatGptState = taskSnapshot?.chatGptState ?: ChatGptState.NotRunning,
        tasks = taskSnapshot?.tasks.orEmpty(),
        usageFreshness = usageFreshness?.status,
        resetFreshness = quota?.upstreamFreshness?.resetInventory?.status,
        lastTransportDataAtMs = lastTransportDataAtMs,
        fiveHourQuota = quota?.fiveHourQuota(nowMs),
        fiveHourQuotaAvailability =
          quota?.fiveHourQuotaAvailability(nowMs) ?: FiveHourQuotaAvailability.Missing,
      )
  }

  private fun syncState(quota: QuotaSnapshot?, computerOnline: Boolean, nowMs: Long): SyncState =
    when {
      !computerOnline -> SyncState.Offline
      quota == null -> SyncState.Cached
      quota.upstreamFreshness?.usage?.status == UpstreamFreshnessStatus.Current ->
        if (quotaConfirmationIsFresh(quota, nowMs)) SyncState.Synced else SyncState.Cached
      quota.upstreamFreshness?.usage?.status == UpstreamFreshnessStatus.Cached ->
        SyncState.Cached
      quota.upstreamFreshness?.usage?.status == UpstreamFreshnessStatus.Unavailable ->
        SyncState.AwaitingConfirmation
      quota.sourceStatus == QuotaSourceStatus.Ok && quota.codexLink == CodexLinkStatus.Ok ->
        if (quotaConfirmationIsFresh(quota, nowMs)) SyncState.Synced else SyncState.Cached
      else -> SyncState.Cached
    }

  private fun quotaConfirmationIsFresh(quota: QuotaSnapshot, nowMs: Long): Boolean {
    val confirmedAtMs = quota.upstreamFreshness?.usage?.lastSuccessAtMs ?: quota.generatedAtMs
    return nowMs - confirmedAtMs <= CURRENT_QUOTA_MAX_AGE_MS
  }

  private fun QuotaSnapshot.weeklyQuota(nowMs: Long): WeeklyQuota? =
    windows
      .singleOrNull { it.name == "weekly" && it.windowMinutes == WEEKLY_WINDOW_MINUTES }
      ?.takeIf {
        it.status == QuotaWindowStatus.Current &&
          it.remainingPercent != null &&
          it.resetsAtMs > nowMs
      }
      ?.let { WeeklyQuota(remainingPercent = it.remainingPercent!!, resetsAtMs = it.resetsAtMs) }

  private fun QuotaSnapshot.fiveHourQuota(nowMs: Long): WeeklyQuota? =
    windows
      .singleOrNull { it.name == "five_hour" && it.windowMinutes == FIVE_HOUR_WINDOW_MINUTES }
      ?.takeIf {
        it.status == QuotaWindowStatus.Current &&
          it.remainingPercent != null &&
          it.resetsAtMs > nowMs
      }
      ?.let { WeeklyQuota(remainingPercent = it.remainingPercent!!, resetsAtMs = it.resetsAtMs) }

  private fun QuotaSnapshot.fiveHourQuotaAvailability(nowMs: Long): FiveHourQuotaAvailability {
    val window =
      windows.singleOrNull {
        it.name == "five_hour" && it.windowMinutes == FIVE_HOUR_WINDOW_MINUTES
      } ?: return FiveHourQuotaAvailability.Missing
    return if (
      window.status == QuotaWindowStatus.Current &&
        window.remainingPercent != null &&
        window.resetsAtMs > nowMs
    ) {
      FiveHourQuotaAvailability.Available
    } else {
      FiveHourQuotaAvailability.Pending
    }
  }

  private fun ResetInventoryStatus.isTrusted(): Boolean =
    this == ResetInventoryStatus.Cached || this == ResetInventoryStatus.CachedDerived

  private fun ResetInventorySnapshot.hasUsableData(nowMs: Long): Boolean =
    availableCount == 0 ||
      (availableCount != null && items.isEmpty()) ||
      items.any { it.expiresAtMs > nowMs }

  private companion object {
    const val WEEKLY_WINDOW_MINUTES = 10_080
    const val FIVE_HOUR_WINDOW_MINUTES = 300
    // Windows confirms every 45 seconds. Two minutes tolerates two delayed attempts without
    // presenting genuinely old quota as current.
    const val CURRENT_QUOTA_MAX_AGE_MS = 120_000L
  }
}
