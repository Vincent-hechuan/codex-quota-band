package com.codex.quota.android.diagnostics

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import com.codex.quota.android.ui.AppUiState
import com.codex.quota.android.ui.DeviceConnections
import com.codex.quota.android.ui.DeviceLinkState
import com.codex.quota.android.ui.SyncState
import com.codex.quota.android.ui.WeeklyQuota
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
  @Test
  fun `diagnostic export contains connection facts but never task titles`() {
    val report =
      DiagnosticReport.render(
        state =
          AppUiState(
            syncState = SyncState.Cached,
            lastSyncAtMs = 1_000,
            weeklyQuota = WeeklyQuota(34, 2_000),
            resetAvailableCount = 1,
            resetCredits = emptyList(),
            connections = DeviceConnections(DeviceLinkState.Connected, DeviceLinkState.Connected, DeviceLinkState.Disconnected),
            chatGptState = ChatGptState.Running,
            tasks = listOf(SyncedTask("conversation-1", "不应导出的任务标题", TaskState.Running, updatedAtMs = 1_000)),
            usageFreshness = UpstreamFreshnessStatus.Cached,
            resetFreshness = UpstreamFreshnessStatus.Current,
            lastTransportDataAtMs = 2_500,
          ),
        generatedAtMs = 3_000,
        appVersion = "0.4.0",
      )

    assertTrue(report.contains("\"syncState\":\"cached\""))
    assertTrue(report.contains("\"computer\":\"connected\""))
    assertTrue(report.contains("\"upstreamUsage\":\"cached\""))
    assertTrue(report.contains("\"upstreamResetInventory\":\"current\""))
    assertTrue(report.contains("\"lastTransportDataAtMs\":2500"))
    assertFalse(report.contains("不应导出的任务标题"))
    assertFalse(report.contains("conversation-1"))
    assertFalse(report.contains("\"tasks\""))
  }
}
