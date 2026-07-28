package com.codex.quota.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SyncRefreshRequestWireContract {
  private val json = Json { encodeDefaults = true }

  fun encode(connectionId: String): String {
    require(connectionId.length in 16..128)
    require(connectionId.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    return json.encodeToString(WireRefreshRequest(connectionId = connectionId))
  }
}

@Serializable
private data class WireRefreshRequest(
  val type: WireRefreshRequestType = WireRefreshRequestType.RefreshRequest,
  val transportVersion: Int = 1,
  val connectionId: String,
  val scope: WireRefreshScope = WireRefreshScope.Quota,
)

@Serializable
private enum class WireRefreshRequestType {
  @SerialName("refresh_request") RefreshRequest,
}

@Serializable
private enum class WireRefreshScope {
  @SerialName("quota") Quota,
}
