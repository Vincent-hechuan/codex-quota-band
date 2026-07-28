package com.codex.quota.android.diagnostics

import com.codex.quota.android.ui.AppUiState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** User-triggered local diagnostic export. Deliberately excludes tasks and all title/content fields. */
object DiagnosticReport {
  fun render(state: AppUiState, generatedAtMs: Long, appVersion: String): String =
    buildJsonObject {
      put("format", "codexquota-diagnostics-v1")
      put("generatedAtMs", generatedAtMs)
      put("appVersion", appVersion)
      put("syncState", state.syncState.name.lowercase())
      state.lastSyncAtMs?.let { put("lastSyncAtMs", it) }
      state.lastTransportDataAtMs?.let { put("lastTransportDataAtMs", it) }
      state.usageFreshness?.let { put("upstreamUsage", it.name.lowercase()) }
      state.resetFreshness?.let { put("upstreamResetInventory", it.name.lowercase()) }
      put("weeklyQuotaAvailable", state.weeklyQuota != null)
      put("resetCountAvailable", state.resetAvailableCount != null)
      put("computer", state.connections.computer.name.lowercase())
      put("phone", state.connections.phone.name.lowercase())
      put("band", state.connections.band.name.lowercase())
      put("chatGpt", state.chatGptState.name.lowercase())
    }.toString()
}
