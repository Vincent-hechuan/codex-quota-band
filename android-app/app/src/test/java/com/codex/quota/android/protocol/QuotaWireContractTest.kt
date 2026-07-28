package com.codex.quota.android.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuotaWireContractTest {
  @Test
  fun decodesTheStrictPrivacyMinimizedQuotaSnapshot() {
    val snapshot = QuotaWireContract.decode(validQuotaPayload())

    assertEquals(Instant.parse("2026-07-24T08:00:00Z").toEpochMilli(), snapshot.generatedAtMs)
    assertEquals(QuotaSourceStatus.Ok, snapshot.sourceStatus)
    assertEquals(61, snapshot.windows.single().remainingPercent)
    assertEquals(2, snapshot.resetInventory.availableCount)
    assertEquals(ComputerLinkStatus.Online, snapshot.computerLink)
  }

  @Test
  fun rejectsUnknownOrPrivateFields() {
    val payload = validQuotaPayload().replace("\"protocolVersion\": 1,", "\"protocolVersion\": 1, \"prompt\": \"private\",")

    assertThrows(Exception::class.java) { QuotaWireContract.decode(payload) }
  }

  @Test
  fun rejectsMalformedTimestampsAndDuplicateIdentities() {
    val malformed = validQuotaPayload().replace("2026-07-24T08:00:00Z", "not-a-date")
    val duplicate =
      validQuotaPayload().replace(
        "\"windows\": [",
        "\"windows\": [{\"id\":\"weekly\",\"name\":\"weekly\",\"windowMinutes\":10080,\"remainingPercent\":40,\"resetsAt\":\"2026-07-31T08:00:00Z\",\"status\":\"current\"},",
      )

    assertThrows(IllegalArgumentException::class.java) { QuotaWireContract.decode(malformed) }
    assertThrows(IllegalArgumentException::class.java) { QuotaWireContract.decode(duplicate) }
  }

  @Test
  fun encodesAQuotaSnapshotWithoutAddingPrivateFields() {
    val encoded = QuotaWireContract.encode(QuotaWireContract.decode(validQuotaPayload()))
    val roundTrip = QuotaWireContract.decode(encoded)

    assertEquals(61, roundTrip.windows.single().remainingPercent)
    assertEquals(2, roundTrip.resetInventory.availableCount)
    assertEquals(Instant.parse("2026-07-31T08:00:00Z").toEpochMilli(), roundTrip.windows.single().resetsAtMs)
    assert(!encoded.contains("prompt"))
    assert(!encoded.contains("token"))
  }

  @Test
  fun decodesV2ResetTimingWithoutAcceptingCardIdentityFields() {
    val snapshot = QuotaWireContract.decode(validV2QuotaPayload(), expectedVersion = 2)
    val item = snapshot.resetInventory.items.single()

    assertEquals(Instant.parse("2026-07-25T09:00:00Z").toEpochMilli(), item.grantedAtMs)
    assertEquals(Instant.parse("2026-07-31T19:49:39.737Z").toEpochMilli(), item.expiresAtMs)
    assertEquals("", item.id)
    assertEquals("", item.title)
  }

  @Test
  fun v2AllowsAnUnknownGrantTimeOnlyAsExplicitNull() {
    val snapshot = QuotaWireContract.decode(validV2QuotaPayload().replace("\"2026-07-25T09:00:00Z\"", "null"), expectedVersion = 2)

    assertEquals(null, snapshot.resetInventory.items.single().grantedAtMs)
  }

  @Test
  fun v2RejectsV1CardIdentityFieldsAndUnknownFields() {
    val withIdentity = validV2QuotaPayload().replace(
      "\"status\": \"available\",",
      "\"id\": \"reset-1\", \"title\": \"Full reset\", \"status\": \"available\",",
    )
    val withPrivateField = validV2QuotaPayload().replace(
      "\"expiresAt\": \"2026-07-31T19:49:39.737Z\"",
      "\"expiresAt\": \"2026-07-31T19:49:39.737Z\", \"description\": \"private\"",
    )

    assertThrows(Exception::class.java) { QuotaWireContract.decode(withIdentity, expectedVersion = 2) }
    assertThrows(Exception::class.java) { QuotaWireContract.decode(withPrivateField, expectedVersion = 2) }
  }

  @Test
  fun v3DecodesIndependentUsageAndResetFreshness() {
    val snapshot = QuotaWireContract.decode(validV3QuotaPayload(), expectedVersion = 3)

    assertEquals(UpstreamFreshnessStatus.Current, snapshot.upstreamFreshness?.usage?.status)
    assertEquals(
      Instant.parse("2026-07-28T10:00:00Z").toEpochMilli(),
      snapshot.upstreamFreshness?.usage?.lastSuccessAtMs,
    )
    assertEquals(UpstreamFreshnessStatus.Cached, snapshot.upstreamFreshness?.resetInventory?.status)
    assertEquals(
      Instant.parse("2026-07-28T09:45:00Z").toEpochMilli(),
      snapshot.upstreamFreshness?.resetInventory?.lastSuccessAtMs,
    )
  }

  @Test
  fun v3RejectsMissingFreshnessUnknownFieldsAndVersionFallback() {
    val missingFreshness =
      validV3QuotaPayload().replace(Regex(",\\s*\"upstreamFreshness\"[\\s\\S]*$"), "\n}")
    val privateField =
      validV3QuotaPayload().replace(
        "\"status\": \"current\",",
        "\"status\": \"current\", \"error\": \"private\",",
      )

    assertThrows(Exception::class.java) {
      QuotaWireContract.decode(missingFreshness, expectedVersion = 3)
    }
    assertThrows(Exception::class.java) {
      QuotaWireContract.decode(privateField, expectedVersion = 3)
    }
    assertThrows(IllegalArgumentException::class.java) {
      QuotaWireContract.decode(validV3QuotaPayload(), expectedVersion = 2)
    }
  }

  private fun validQuotaPayload(): String =
    """
    {
      "protocolVersion": 1,
      "generatedAt": "2026-07-24T08:00:00Z",
      "sourceStatus": "ok",
      "limitsCollectedAt": "2026-07-24T07:59:59Z",
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
        "availableCount": 2,
        "cachedAt": "2026-07-24T07:55:00Z",
        "items": [
          {"id": "reset-1", "title": "Full reset", "status": "available", "expiresAt": "2026-08-01T00:00:00Z"}
        ]
      },
      "link": {"computer": "online", "codex": "ok"}
    }
    """.trimIndent()

  private fun validV2QuotaPayload(): String =
    """
    {
      "protocolVersion": 2,
      "generatedAt": "2026-07-26T10:00:00Z",
      "sourceStatus": "ok",
      "limitsCollectedAt": "2026-07-26T09:59:59Z",
      "windows": [
        {
          "id": "weekly",
          "name": "weekly",
          "windowMinutes": 10080,
          "remainingPercent": 61,
          "resetsAt": "2026-08-01T00:00:00Z",
          "status": "current"
        }
      ],
      "resetInventory": {
        "status": "cached",
        "availableCount": 1,
        "cachedAt": "2026-07-26T10:00:00Z",
        "items": [
          {"status": "available", "grantedAt": "2026-07-25T09:00:00Z", "expiresAt": "2026-07-31T19:49:39.737Z"}
        ]
      },
      "link": {"computer": "online", "codex": "ok"}
    }
    """.trimIndent()

  private fun validV3QuotaPayload(): String =
    validV2QuotaPayload()
      .replaceFirst("\"protocolVersion\": 2", "\"protocolVersion\": 3")
      .dropLast(1) +
      """,
      "upstreamFreshness": {
        "usage": {
          "status": "current",
          "lastAttemptAt": "2026-07-28T10:00:00Z",
          "lastSuccessAt": "2026-07-28T10:00:00Z"
        },
        "resetInventory": {
          "status": "cached",
          "lastAttemptAt": "2026-07-28T10:00:00Z",
          "lastSuccessAt": "2026-07-28T09:45:00Z"
        }
      }
    }
    """.trimIndent()
}
