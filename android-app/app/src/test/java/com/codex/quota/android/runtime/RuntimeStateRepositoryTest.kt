package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.ui.DeviceLinkState
import com.codex.quota.android.ui.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeStateRepositoryTest {
  private val nowMs = Instant.parse("2026-07-24T08:00:00Z").toEpochMilli()

  @Test
  fun acceptedSnapshotsDriveTheCombinedUiState() {
    val repository = RuntimeStateRepository { nowMs }

    assertEquals(IngestResult.Accepted, repository.ingestQuota(quotaPayload()))
    assertEquals(IngestResult.Accepted, repository.ingestTasks(taskPayload(sequence = 4)))

    val state = repository.state.value
    assertEquals(SyncState.Synced, state.syncState)
    assertEquals(DeviceLinkState.Connected, state.connections.computer)
    assertEquals(61, state.weeklyQuota?.remainingPercent)
    assertEquals(2, state.resetAvailableCount)
    assertEquals(1, state.resetCredits.size)
    assertEquals(ChatGptState.Running, state.chatGptState)
    assertEquals("整理素材", state.tasks.single().title)
  }

  @Test
  fun staleQuotaAndTaskSnapshotsCannotRollStateBack() {
    val repository = RuntimeStateRepository { nowMs }
    repository.ingestQuota(quotaPayload(generatedAt = "2026-07-24T08:00:00Z", remaining = 61))
    repository.ingestTasks(taskPayload(sequence = 4, generatedAtMs = nowMs))

    assertEquals(
      IngestResult.IgnoredStale,
      repository.ingestQuota(quotaPayload(generatedAt = "2026-07-24T07:59:59Z", remaining = 99)),
    )
    assertEquals(
      IngestResult.IgnoredStale,
      repository.ingestTasks(taskPayload(sequence = 3, generatedAtMs = nowMs + 1)),
    )
    assertEquals(61, repository.state.value.weeklyQuota?.remainingPercent)
    assertEquals(4, repository.state.value.tasks.single().updatedAtMs / 1_000 % 10)
  }

  @Test
  fun aNewTransportConnectionAllowsTheWindowsSequenceToRestart() {
    val repository = RuntimeStateRepository { nowMs }
    repository.ingestTasks(taskPayload(sequence = 99, generatedAtMs = nowMs))
    repository.markTransportDisconnected()

    val result = repository.ingestTasks(taskPayload(sequence = 0, generatedAtMs = nowMs + 1))

    assertEquals(IngestResult.Accepted, result)
    assertEquals(DeviceLinkState.Connected, repository.state.value.connections.computer)
  }

  @Test
  fun disconnectKeepsTrustedDataButLabelsItOffline() {
    val repository = RuntimeStateRepository { nowMs }
    repository.ingestQuota(quotaPayload())

    repository.markTransportDisconnected()

    val state = repository.state.value
    assertEquals(SyncState.Offline, state.syncState)
    assertEquals(DeviceLinkState.Disconnected, state.connections.computer)
    assertEquals(61, state.weeklyQuota?.remainingPercent)
    assertEquals(1, state.resetCredits.size)
  }

  @Test
  fun partialRefreshRetainsOnlyUnexpiredTrustedResetData() {
    var clockMs = nowMs
    val repository = RuntimeStateRepository { clockMs }
    repository.ingestQuota(quotaPayload())

    repository.ingestQuota(
      quotaPayload(
        generatedAt = "2026-07-24T08:00:01Z",
        remaining = 60,
        sourceStatus = "partial",
        resetInventory = missingInventory,
      ),
    )
    assertEquals(SyncState.Cached, repository.state.value.syncState)
    assertEquals(2, repository.state.value.resetAvailableCount)
    assertEquals(1, repository.state.value.resetCredits.size)

    clockMs = Instant.parse("2026-08-02T00:00:00Z").toEpochMilli()
    repository.markTransportDisconnected()
    assertNull(repository.state.value.resetAvailableCount)
    assertEquals(emptyList<Any>(), repository.state.value.resetCredits)
  }

  @Test
  fun pendingOrExpiredWindowsNeverBecomeGuessedWeeklyQuota() {
    val repository = RuntimeStateRepository { nowMs }

    repository.ingestQuota(quotaPayload(windowStatus = "pending_sync", remaining = null))

    assertNull(repository.state.value.weeklyQuota)
  }

  @Test
  fun v3SeparatesComputerTransportFromUpstreamQuotaFreshness() {
    var clockMs = nowMs
    val repository = RuntimeStateRepository { clockMs }
    val confirmedAt = nowMs

    repository.ingestQuota(
      v3QuotaPayload(
        generatedAt = "2026-07-24T08:00:00Z",
        usageStatus = "current",
        lastAttemptAt = "2026-07-24T08:00:00Z",
        lastSuccessAt = "2026-07-24T08:00:00Z",
      ),
    )

    var state = repository.state.value
    assertEquals(DeviceLinkState.Connected, state.connections.computer)
    assertEquals(SyncState.Synced, state.syncState)
    assertEquals(confirmedAt, state.lastSyncAtMs)
    assertEquals(nowMs, state.lastTransportDataAtMs)

    clockMs += 1_000
    repository.ingestQuota(
      v3QuotaPayload(
        generatedAt = "2026-07-24T08:00:01Z",
        usageStatus = "cached",
        lastAttemptAt = "2026-07-24T08:00:01Z",
        lastSuccessAt = "2026-07-24T08:00:00Z",
      ),
    )
    state = repository.state.value
    assertEquals(DeviceLinkState.Connected, state.connections.computer)
    assertEquals(SyncState.Cached, state.syncState)
    assertEquals(confirmedAt, state.lastSyncAtMs)
    assertEquals(clockMs, state.lastTransportDataAtMs)

    clockMs += 1_000
    repository.ingestQuota(
      v3QuotaPayload(
        generatedAt = "2026-07-24T08:00:02Z",
        usageStatus = "unavailable",
        lastAttemptAt = "2026-07-24T08:00:02Z",
        lastSuccessAt = null,
      ),
    )
    state = repository.state.value
    assertEquals(DeviceLinkState.Connected, state.connections.computer)
    assertEquals(SyncState.AwaitingConfirmation, state.syncState)
    assertNull(state.lastSyncAtMs)

    repository.markTransportDisconnected()
    assertEquals(SyncState.Offline, repository.state.value.syncState)
  }

  @Test
  fun currentQuotaBecomesCachedWhenItsUpstreamConfirmationIsOlderThanOneMinute() {
    var clockMs = nowMs
    val repository = RuntimeStateRepository { clockMs }

    repository.ingestQuota(
      v3QuotaPayload(
        generatedAt = "2026-07-24T08:00:00Z",
        usageStatus = "current",
        lastAttemptAt = "2026-07-24T08:00:00Z",
        lastSuccessAt = "2026-07-24T08:00:00Z",
      ),
    )
    assertEquals(SyncState.Synced, repository.state.value.syncState)

    clockMs += 60_001
    repository.reassessQuotaFreshness()

    assertEquals(SyncState.Cached, repository.state.value.syncState)
    assertEquals(nowMs, repository.state.value.lastSyncAtMs)
  }

  @Test
  fun locallyRemovedTaskStaysHiddenUntilItChangesAndThenReappears() {
    val repository = RuntimeStateRepository { nowMs }
    repository.ingestTasks(taskPayload(sequence = 4, state = "waiting_for_review"))

    repository.removeTaskFromBoard("session-42")

    assertEquals(emptyList<Any>(), repository.state.value.tasks)
    assertEquals(emptyList<Any>(), repository.latestTaskSnapshot()?.tasks)

    repository.ingestTasks(
      taskPayload(
        sequence = 5,
        generatedAtMs = nowMs + 1,
        state = "needs_authorization",
        updatedAtMs = nowMs + 5_000,
      ),
    )

    assertEquals("整理素材", repository.state.value.tasks.single().title)
    assertEquals(TaskState.NeedsAuthorization, repository.state.value.tasks.single().state)
  }

  private fun taskPayload(
    sequence: Long,
    generatedAtMs: Long = nowMs,
    state: String = "running",
    updatedAtMs: Long = nowMs + 4_000,
  ): String =
    """
    {
      "protocolVersion": 1,
      "sequence": $sequence,
      "generatedAtMs": $generatedAtMs,
      "chatGptState": "running",
      "chatGptFocused": false,
      "tasks": [
        {
          "conversationId": "session-42",
          "title": "整理素材",
          "state": "$state",
          "activity": "modifying_files",
          "updatedAtMs": $updatedAtMs
        }
      ]
    }
    """.trimIndent()

  private fun quotaPayload(
    generatedAt: String = "2026-07-24T08:00:00Z",
    remaining: Int? = 61,
    sourceStatus: String = "ok",
    windowStatus: String = "current",
    resetInventory: String = trustedInventory,
  ): String =
    """
    {
      "protocolVersion": 1,
      "generatedAt": "$generatedAt",
      "sourceStatus": "$sourceStatus",
      "limitsCollectedAt": "$generatedAt",
      "windows": [
        {
          "id": "weekly",
          "name": "weekly",
          "windowMinutes": 10080,
          "remainingPercent": ${remaining ?: "null"},
          "resetsAt": "2026-07-31T08:00:00Z",
          "status": "$windowStatus"
        }
      ],
      "resetInventory": $resetInventory,
      "link": {"computer": "online", "codex": "${if (sourceStatus == "ok") "ok" else "stale"}"}
    }
    """.trimIndent()

  private fun v3QuotaPayload(
    generatedAt: String,
    usageStatus: String,
    lastAttemptAt: String?,
    lastSuccessAt: String?,
  ): String =
    """
    {
      "protocolVersion": 3,
      "generatedAt": "$generatedAt",
      "sourceStatus": "ok",
      "limitsCollectedAt": "$generatedAt",
      "windows": [
        {
          "id": "weekly",
          "name": "weekly",
          "windowMinutes": 10080,
          "remainingPercent": 61,
          "resetsAt": "2026-07-31T08:00:00Z",
          "status": "current"
        }
      ],
      "resetInventory": {
        "status": "cached",
        "availableCount": 0,
        "cachedAt": "$generatedAt",
        "items": []
      },
      "link": {"computer": "online", "codex": "${if (usageStatus == "current") "ok" else "stale"}"},
      "upstreamFreshness": {
        "usage": {
          "status": "$usageStatus",
          "lastAttemptAt": ${lastAttemptAt?.let { "\"$it\"" } ?: "null"},
          "lastSuccessAt": ${lastSuccessAt?.let { "\"$it\"" } ?: "null"}
        },
        "resetInventory": {
          "status": "current",
          "lastAttemptAt": "$generatedAt",
          "lastSuccessAt": "$generatedAt"
        }
      }
    }
    """.trimIndent()

  private val trustedInventory =
    """
    {
      "status": "cached",
      "availableCount": 2,
      "cachedAt": "2026-07-24T07:55:00Z",
      "items": [
        {"id": "reset-1", "title": "Full reset", "status": "available", "expiresAt": "2026-08-01T00:00:00Z"}
      ]
    }
    """.trimIndent()

  private val missingInventory =
    """
    {"status": "unavailable", "availableCount": null, "cachedAt": null, "items": []}
    """.trimIndent()
}
