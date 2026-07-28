package com.codex.quota.android.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStreamWireContractTest {
  @Test
  fun negotiatesOnlyTheSupportedTransportAndSnapshotVersions() {
    val frame =
      SyncStreamWireContract.decode(
        """
        {
          "type": "server_hello",
          "transportVersion": 1,
          "connectionId": "connection_0123456789",
          "quotaVersion": 1,
          "taskVersion": 1,
          "heartbeatIntervalMs": 15000
        }
        """.trimIndent(),
      )

    assertEquals("connection_0123456789", frame.connectionId)
    assertEquals(1, (frame as SyncStreamFrame.ServerHello).quotaVersion)
    assertEquals(15000, (frame as SyncStreamFrame.ServerHello).heartbeatIntervalMs)
  }

  @Test
  fun acceptsServerHelloQuotaVersionTwo() {
    val frame =
      SyncStreamWireContract.decode(
        """
        {
          "type": "server_hello",
          "transportVersion": 1,
          "connectionId": "connection_0123456789",
          "quotaVersion": 2,
          "taskVersion": 1,
          "heartbeatIntervalMs": 15000
        }
        """.trimIndent(),
      ) as SyncStreamFrame.ServerHello

    assertEquals(2, frame.quotaVersion)
  }

  @Test
  fun acceptsServerHelloQuotaVersionThree() {
    val frame =
      SyncStreamWireContract.decode(
        """
        {
          "type": "server_hello",
          "transportVersion": 1,
          "connectionId": "connection_0123456789",
          "quotaVersion": 3,
          "taskVersion": 1,
          "heartbeatIntervalMs": 15000
        }
        """.trimIndent(),
      ) as SyncStreamFrame.ServerHello

    assertEquals(3, frame.quotaVersion)
  }

  @Test
  fun decodesACombinedSnapshotThroughBothStrictNestedContracts() {
    val frame = SyncStreamWireContract.decode(snapshotFrame(), negotiatedQuotaVersion = 1) as SyncStreamFrame.Snapshot

    assertEquals(8, frame.sequence)
    assertEquals(67, frame.quota.windows.single().remainingPercent)
    assertEquals("任务", frame.tasks.tasks.single().title)
  }

  @Test
  fun rejectsUnknownPrivateFieldsAtEveryFrameLevel() {
    val outerPrivate = snapshotFrame().replace("\"sequence\": 8,", "\"sequence\": 8, \"token\": \"secret\",")
    val nestedPrivate = snapshotFrame().replace("\"chatGptFocused\": false,", "\"chatGptFocused\": false, \"prompt\": \"private\",")

    assertThrows(Exception::class.java) { SyncStreamWireContract.decode(outerPrivate, negotiatedQuotaVersion = 1) }
    assertThrows(Exception::class.java) { SyncStreamWireContract.decode(nestedPrivate, negotiatedQuotaVersion = 1) }
  }

  @Test
  fun rejectsNestedSnapshotsThatClaimToBeFromTheFuture() {
    val future = snapshotFrame(taskGeneratedAtMs = 1784880000001)

    assertThrows(IllegalArgumentException::class.java) { SyncStreamWireContract.decode(future, negotiatedQuotaVersion = 1) }
  }

  @Test
  fun rejectsSnapshotWithoutServerHelloNegotiation() {
    assertThrows(IllegalArgumentException::class.java) {
      SyncStreamWireContract.decode(snapshotFrame())
    }
  }

  @Test
  fun decodesV2OnlyWhenTheNegotiatedVersionMatches() {
    val frame = SyncStreamWireContract.decode(v2SnapshotFrame(), negotiatedQuotaVersion = 2) as SyncStreamFrame.Snapshot

    assertEquals(Instant.parse("2026-07-25T09:00:00Z").toEpochMilli(), frame.quota.resetInventory.items.single().grantedAtMs)
    assertThrows(IllegalArgumentException::class.java) {
      SyncStreamWireContract.decode(v2SnapshotFrame(), negotiatedQuotaVersion = 1)
    }
  }

  @Test
  fun decodesV3OnlyWhenTheNegotiatedVersionMatches() {
    val frame =
      SyncStreamWireContract.decode(
        v3SnapshotFrame(),
        negotiatedQuotaVersion = 3,
      ) as SyncStreamFrame.Snapshot

    assertEquals(UpstreamFreshnessStatus.Cached, frame.quota.upstreamFreshness?.usage?.status)
    assertThrows(IllegalArgumentException::class.java) {
      SyncStreamWireContract.decode(v3SnapshotFrame(), negotiatedQuotaVersion = 2)
    }
  }

  private fun snapshotFrame(taskGeneratedAtMs: Long = 1784880000000): String =
    """
    {
      "type": "snapshot",
      "transportVersion": 1,
      "connectionId": "connection_0123456789",
      "sequence": 8,
      "generatedAtMs": 1784880000000,
      "quota": {
        "protocolVersion": 1,
        "generatedAt": "2026-07-24T08:00:00Z",
        "sourceStatus": "ok",
        "limitsCollectedAt": "2026-07-24T08:00:00Z",
        "windows": [
          {
            "id": "weekly",
            "name": "weekly",
            "windowMinutes": 10080,
            "remainingPercent": 67,
            "resetsAt": "2026-07-31T08:00:00Z",
            "status": "current"
          }
        ],
        "resetInventory": {"status": "missing", "availableCount": null, "cachedAt": null, "items": []},
        "link": {"computer": "online", "codex": "ok"}
      },
      "tasks": {
        "protocolVersion": 1,
        "sequence": 3,
        "generatedAtMs": $taskGeneratedAtMs,
        "chatGptState": "running",
        "chatGptFocused": false,
        "tasks": [
          {"conversationId": "session-1", "title": "任务", "state": "running", "updatedAtMs": 1784880000000}
        ]
      }
    }
    """.trimIndent()

  private fun v2SnapshotFrame(): String =
    snapshotFrame().replaceFirst(
      "\"protocolVersion\": 1,",
      "\"protocolVersion\": 2,",
    ).replace(
      "{\"status\": \"missing\", \"availableCount\": null, \"cachedAt\": null, \"items\": []}",
      "{\"status\": \"cached\", \"availableCount\": 1, \"cachedAt\": \"2026-07-26T10:00:00Z\", \"items\": [{\"status\": \"available\", \"grantedAt\": \"2026-07-25T09:00:00Z\", \"expiresAt\": \"2026-07-31T19:49:39.737Z\"}]}",
    )

  private fun v3SnapshotFrame(): String =
    v2SnapshotFrame()
      .replaceFirst("\"protocolVersion\": 2", "\"protocolVersion\": 3")
      .replace(
        "\"link\": {\"computer\": \"online\", \"codex\": \"ok\"}",
        """
        "link": {"computer": "online", "codex": "stale"},
        "upstreamFreshness": {
          "usage": {"status": "cached", "lastAttemptAt": "2026-07-28T10:00:00Z", "lastSuccessAt": "2026-07-28T09:45:00Z"},
          "resetInventory": {"status": "current", "lastAttemptAt": "2026-07-28T10:00:00Z", "lastSuccessAt": "2026-07-28T10:00:00Z"}
        }
        """.trimIndent(),
      )

}
