package com.codex.quota.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SyncClientHelloWireContract {
  private val json = Json { explicitNulls = true }

  fun encode(clientInstanceId: String): String {
    require(
      clientInstanceId.length in 16..128 &&
        clientInstanceId.all { it.isLetterOrDigit() || it == '_' || it == '-' },
    ) {
      "invalid client identity"
    }
    return json.encodeToString(
      WireClientHello(
        type = WireClientHelloType.ClientHello,
        transportVersion = 1,
        clientInstanceId = clientInstanceId,
        supportedQuotaVersions = listOf(1, 2, 3),
        supportedTaskVersions = listOf(1),
      ),
    )
  }
}

@Serializable
private data class WireClientHello(
  val type: WireClientHelloType,
  val transportVersion: Int,
  val clientInstanceId: String,
  val supportedQuotaVersions: List<Int>,
  val supportedTaskVersions: List<Int>,
)

@Serializable
private enum class WireClientHelloType {
  @SerialName("client_hello") ClientHello,
}
