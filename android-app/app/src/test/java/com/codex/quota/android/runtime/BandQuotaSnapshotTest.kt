package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.CodexLinkStatus
import com.codex.quota.android.protocol.ComputerLinkStatus
import com.codex.quota.android.protocol.QuotaSnapshot
import com.codex.quota.android.protocol.QuotaSourceStatus
import com.codex.quota.android.protocol.QuotaWindow
import com.codex.quota.android.protocol.QuotaWindowStatus
import com.codex.quota.android.protocol.ResetInventoryItem
import com.codex.quota.android.protocol.ResetInventorySnapshot
import com.codex.quota.android.protocol.ResetInventoryStatus
import com.codex.quota.android.protocol.UpstreamDatasetFreshness
import com.codex.quota.android.protocol.UpstreamFreshness
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import java.time.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class BandQuotaSnapshotTest {
  @Test
  fun wearableSummaryContainsCardTimingButNeverV1CardIdentity() {
    val payload =
      buildBandQuotaSnapshot(
        QuotaSnapshot(
          generatedAtMs = Instant.parse("2026-07-26T10:00:00Z").toEpochMilli(),
          sourceStatus = QuotaSourceStatus.Ok,
          limitsCollectedAtMs = null,
          windows = listOf(QuotaWindow("weekly", "weekly", 10_080, 61, Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), QuotaWindowStatus.Current)),
          resetInventory =
            ResetInventorySnapshot(
              status = ResetInventoryStatus.Cached,
              availableCount = 1,
              cachedAtMs = null,
              items = listOf(
                ResetInventoryItem(
                  id = "reset-secret",
                  title = "Full reset",
                  grantedAtMs = Instant.parse("2026-07-25T09:00:00Z").toEpochMilli(),
                  expiresAtMs = Instant.parse("2026-07-31T19:49:39.737Z").toEpochMilli(),
                ),
              ),
            ),
          computerLink = ComputerLinkStatus.Online,
          codexLink = CodexLinkStatus.Ok,
        ),
      ).jsonObject

    val item = payload.getValue("resetInventory").jsonObject.getValue("items").jsonArray.single().jsonObject
    assertEquals("2026-07-25T09:00:00Z", item.getValue("grantedAt").jsonPrimitive.content)
    assertEquals("2026-07-31T19:49:39.737Z", item.getValue("expiresAt").jsonPrimitive.content)
    assertFalse(item.containsKey("id"))
    assertFalse(item.containsKey("title"))
    assertFalse(payload.toString().contains("reset-secret"))
    assertFalse(payload.toString().contains("Full reset"))
  }

  @Test
  fun wearableSummaryDowngradesCachedV3UsageWithoutForwardingFreshnessDetails() {
    val snapshot =
      QuotaSnapshot(
        generatedAtMs = 1_000,
        sourceStatus = QuotaSourceStatus.Ok,
        limitsCollectedAtMs = 900,
        windows = emptyList(),
        resetInventory =
          ResetInventorySnapshot(
            ResetInventoryStatus.Cached,
            0,
            900,
            emptyList(),
          ),
        computerLink = ComputerLinkStatus.Online,
        codexLink = CodexLinkStatus.Stale,
        upstreamFreshness =
          UpstreamFreshness(
            usage =
              UpstreamDatasetFreshness(
                UpstreamFreshnessStatus.Cached,
                1_000,
                500,
              ),
            resetInventory =
              UpstreamDatasetFreshness(
                UpstreamFreshnessStatus.Current,
                1_000,
                1_000,
              ),
          ),
      )

    val payload = buildBandQuotaSnapshot(snapshot).jsonObject

    assertEquals("partial", payload.getValue("sourceStatus").jsonPrimitive.content)
    assertFalse(payload.containsKey("upstreamFreshness"))
    assertFalse(payload.toString().contains("lastAttemptAt"))
    assertFalse(payload.toString().contains("lastSuccessAt"))
  }

  @Test
  fun wearableSummaryDowngradesExpiredCurrentUsageWithoutForwardingFreshnessDetails() {
    val snapshot =
      QuotaSnapshot(
        generatedAtMs = 1_000,
        sourceStatus = QuotaSourceStatus.Ok,
        limitsCollectedAtMs = 900,
        windows = emptyList(),
        resetInventory = ResetInventorySnapshot(ResetInventoryStatus.Cached, 0, 900, emptyList()),
        computerLink = ComputerLinkStatus.Online,
        codexLink = CodexLinkStatus.Ok,
        upstreamFreshness =
          UpstreamFreshness(
            usage = UpstreamDatasetFreshness(UpstreamFreshnessStatus.Current, 1_000, 1_000),
            resetInventory = UpstreamDatasetFreshness(UpstreamFreshnessStatus.Current, 1_000, 1_000),
          ),
      )

    val payload = buildBandQuotaSnapshot(snapshot, nowMs = 61_001).jsonObject

    assertEquals("partial", payload.getValue("sourceStatus").jsonPrimitive.content)
    assertFalse(payload.toString().contains("upstreamFreshness"))
    assertFalse(payload.toString().contains("lastSuccessAt"))
  }

  @Test
  fun demoFiveHourQuotaForBandAddsOnlyTheDisplayWindow() {
    val weekly =
      QuotaWindow(
        id = "weekly",
        name = "weekly",
        windowMinutes = 10_080,
        remainingPercent = 81,
        resetsAtMs = 9_000_000,
        status = QuotaWindowStatus.Current,
      )
    val original =
      QuotaSnapshot(
        generatedAtMs = 1_000,
        sourceStatus = QuotaSourceStatus.Ok,
        limitsCollectedAtMs = 900,
        windows = listOf(weekly),
        resetInventory = ResetInventorySnapshot(ResetInventoryStatus.Cached, 1, 900, emptyList()),
        computerLink = ComputerLinkStatus.Online,
        codexLink = CodexLinkStatus.Ok,
      )

    val demo = original.withDemoFiveHourQuota(nowMs = 2_000)

    assertEquals(listOf(weekly), original.windows)
    assertEquals(2, demo.windows.size)
    assertEquals(weekly, demo.windows.first())
    assertEquals("five_hour", demo.windows.last().name)
    assertEquals(300, demo.windows.last().windowMinutes)
    assertEquals(68, demo.windows.last().remainingPercent)
    assertEquals(10_802_000, demo.windows.last().resetsAtMs)
    assertEquals(QuotaWindowStatus.Current, demo.windows.last().status)
    assertEquals(original.resetInventory, demo.resetInventory)
  }
}
