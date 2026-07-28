package com.codex.quota.android.runtime

import com.codex.quota.android.ui.DeviceLinkState
import com.codex.quota.android.ui.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStreamSessionTest {
  private val nowMs = Instant.parse("2026-07-24T08:00:00Z").toEpochMilli()

  @Test
  fun acceptsOnlySequencedMessagesFromTheActiveConnection() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)
    session.ingest(serverHello("connection_0123456789"))

    assertEquals(
      StreamIngestResult.Accepted,
      session.ingest(snapshot("connection_0123456789", sequence = 8, taskSequence = 8)),
    )
    assertEquals(
      StreamIngestResult.IgnoredStale,
      session.ingest(snapshot("connection_0123456789", sequence = 7, taskSequence = 7)),
    )
    assertEquals(
      StreamIngestResult.IgnoredWrongConnection,
      session.ingest(snapshot("connection_9876543210", sequence = 9, taskSequence = 9)),
    )
    assertEquals(DeviceLinkState.Connected, repository.state.value.connections.computer)
  }

  @Test
  fun aNewConnectionCanRestartBothTransportAndTaskSequences() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)
    session.ingest(serverHello("connection_0123456789"))
    session.ingest(snapshot("connection_0123456789", sequence = 99, taskSequence = 99))

    assertEquals(StreamIngestResult.Accepted, session.ingest(serverHello("connection_9876543210")))
    assertEquals(
      StreamIngestResult.Accepted,
      session.ingest(
        snapshot(
          connectionId = "connection_9876543210",
          sequence = 0,
          taskSequence = 0,
          generatedAtMs = nowMs + 1,
        ),
      ),
    )
  }

  @Test
  fun reconnectRecordsFreshTransportActivityEvenWhenPayloadDataIsUnchanged() {
    var clockMs = nowMs
    val repository = RuntimeStateRepository { clockMs }
    val session = SyncStreamSession(repository)
    session.ingest(serverHello("connection_0123456789"))
    session.ingest(snapshot("connection_0123456789", sequence = 1, taskSequence = 1))

    clockMs += 2_000
    session.ingest(serverHello("connection_9876543210"))
    session.ingest(snapshot("connection_9876543210", sequence = 0, taskSequence = 0))

    assertEquals(clockMs, repository.state.value.lastTransportDataAtMs)
  }

  @Test
  fun disconnectPreservesDataAndMarksTheComputerOffline() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)
    session.ingest(serverHello("connection_0123456789"))
    session.ingest(snapshot("connection_0123456789", sequence = 1, taskSequence = 1))

    session.disconnect()

    assertEquals(DeviceLinkState.Disconnected, repository.state.value.connections.computer)
    assertEquals(52, repository.state.value.weeklyQuota?.remainingPercent)
  }

  @Test
  fun acceptedTaskSnapshotsReachAlertsWithReconnectContext() {
    val repository = RuntimeStateRepository { nowMs }
    val reconnectFlags = mutableListOf<Boolean>()
    val session = SyncStreamSession(repository) { _, reconnect -> reconnectFlags += reconnect }
    session.ingest(serverHello("connection_0123456789"))

    session.ingest(snapshot("connection_0123456789", sequence = 1, taskSequence = 1))
    session.ingest(snapshot("connection_0123456789", sequence = 2, taskSequence = 2))
    session.ingest(snapshot("connection_0123456789", sequence = 2, taskSequence = 2))

    assertEquals(listOf(true, false), reconnectFlags)
  }

  @Test
  fun snapshotRequiresHelloAndDisconnectClearsQuotaNegotiation() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)

    assertThrows(IllegalArgumentException::class.java) {
      session.ingest(snapshot("connection_0123456789", sequence = 1, taskSequence = 1))
    }
    session.ingest(serverHello("connection_0123456789"))
    session.disconnect()
    assertThrows(IllegalArgumentException::class.java) {
      session.ingest(snapshot("connection_0123456789", sequence = 2, taskSequence = 2))
    }
  }

  @Test
  fun negotiatedV2SnapshotPreservesGrantTime() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)

    session.ingest(serverHello("connection_0123456789", quotaVersion = 2))
    assertEquals(
      StreamIngestResult.Accepted,
      session.ingest(v2Snapshot("connection_0123456789", sequence = 1, taskSequence = 1)),
    )
    assertEquals(
      Instant.parse("2026-07-25T09:00:00Z").toEpochMilli(),
      repository.state.value.resetCredits.single().grantedAtMs,
    )
  }

  @Test
  fun negotiatedV3SnapshotDrivesUpstreamStateAndDisconnectClearsNegotiation() {
    val repository = RuntimeStateRepository { nowMs }
    val session = SyncStreamSession(repository)

    session.ingest(serverHello("connection_0123456789", quotaVersion = 3))
    assertEquals(
      StreamIngestResult.Accepted,
      session.ingest(v3Snapshot("connection_0123456789", sequence = 1, taskSequence = 1)),
    )
    assertEquals(SyncState.Cached, repository.state.value.syncState)

    session.disconnect()
    assertThrows(IllegalArgumentException::class.java) {
      session.ingest(v3Snapshot("connection_0123456789", sequence = 2, taskSequence = 2))
    }
  }

  private fun serverHello(connectionId: String, quotaVersion: Int = 1): String =
    """
    {
      "type": "server_hello",
      "transportVersion": 1,
      "connectionId": "$connectionId",
      "quotaVersion": $quotaVersion,
      "taskVersion": 1,
      "heartbeatIntervalMs": 15000
    }
    """.trimIndent()

  private fun snapshot(
    connectionId: String,
    sequence: Long,
    taskSequence: Long,
    generatedAtMs: Long = nowMs,
  ): String =
    """
    {
      "type": "snapshot",
      "transportVersion": 1,
      "connectionId": "$connectionId",
      "sequence": $sequence,
      "generatedAtMs": $generatedAtMs,
      "quota": {
        "protocolVersion": 1,
        "generatedAt": "${Instant.ofEpochMilli(generatedAtMs)}",
        "sourceStatus": "ok",
        "limitsCollectedAt": "${Instant.ofEpochMilli(generatedAtMs)}",
        "windows": [
          {
            "id": "weekly",
            "name": "weekly",
            "windowMinutes": 10080,
            "remainingPercent": 52,
            "resetsAt": "2026-07-31T08:00:00Z",
            "status": "current"
          }
        ],
        "resetInventory": {"status": "missing", "availableCount": null, "cachedAt": null, "items": []},
        "link": {"computer": "online", "codex": "ok"}
      },
      "tasks": {
        "protocolVersion": 1,
        "sequence": $taskSequence,
        "generatedAtMs": $generatedAtMs,
        "chatGptState": "running",
        "chatGptFocused": false,
        "tasks": []
      }
    }
    """.trimIndent()

  private fun v2Snapshot(connectionId: String, sequence: Long, taskSequence: Long): String =
    snapshot(connectionId, sequence, taskSequence)
      .replaceFirst("\"protocolVersion\": 1,", "\"protocolVersion\": 2,")
      .replace(
        "{\"status\": \"missing\", \"availableCount\": null, \"cachedAt\": null, \"items\": []}",
        "{\"status\": \"cached\", \"availableCount\": 1, \"cachedAt\": \"2026-07-26T10:00:00Z\", \"items\": [{\"status\": \"available\", \"grantedAt\": \"2026-07-25T09:00:00Z\", \"expiresAt\": \"2026-08-01T00:00:00Z\"}]}",
      )

  private fun v3Snapshot(connectionId: String, sequence: Long, taskSequence: Long): String =
    v2Snapshot(connectionId, sequence, taskSequence)
      .replaceFirst("\"protocolVersion\": 2,", "\"protocolVersion\": 3,")
      .replace(
        "\"link\": {\"computer\": \"online\", \"codex\": \"ok\"}",
        """
        "link": {"computer": "online", "codex": "stale"},
        "upstreamFreshness": {
          "usage": {"status": "cached", "lastAttemptAt": "2026-07-24T08:00:00Z", "lastSuccessAt": "2026-07-24T07:45:00Z"},
          "resetInventory": {"status": "current", "lastAttemptAt": "2026-07-24T08:00:00Z", "lastSuccessAt": "2026-07-24T08:00:00Z"}
        }
        """.trimIndent(),
      )
}
