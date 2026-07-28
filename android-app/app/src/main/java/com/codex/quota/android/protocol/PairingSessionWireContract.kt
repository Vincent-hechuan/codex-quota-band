package com.codex.quota.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PairingSessionWireContract {
  private val json =
    Json {
      ignoreUnknownKeys = false
      explicitNulls = true
    }

  fun encodeRequest(code: String, clientInstanceId: String): String {
    require(code.length == 6 && code.all(Char::isDigit)) { "invalid pairing code" }
    requireConnectionId(clientInstanceId)
    return json.encodeToString(
      WirePairRequest(
        type = WirePairRequestType.PairRequest,
        protocolVersion = PROTOCOL_VERSION,
        code = code,
        clientInstanceId = clientInstanceId,
      ),
    )
  }

  fun decodeSuccess(payload: String): String {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "pairing response is too large"
    }
    val response = json.decodeFromString<WirePairSuccess>(payload)
    require(response.protocolVersion == PROTOCOL_VERSION) { "unsupported pairing protocol" }
    require(
      response.phoneToken.length == 64 && response.phoneToken.all(Char::isLowerHexDigit),
    ) {
      "invalid phone token"
    }
    return response.phoneToken
  }

  private fun requireConnectionId(value: String) {
    require(
      value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' },
    ) {
      "invalid client identity"
    }
  }

  private const val PROTOCOL_VERSION = 1
  private const val MAX_PAYLOAD_BYTES = 4 * 1024
}

@Serializable
private data class WirePairRequest(
  val type: WirePairRequestType,
  val protocolVersion: Int,
  val code: String,
  val clientInstanceId: String,
)

@Serializable
private enum class WirePairRequestType {
  @SerialName("pair_request") PairRequest,
}

@Serializable
private data class WirePairSuccess(
  val type: WirePairSuccessType,
  val protocolVersion: Int,
  val phoneToken: String,
)

@Serializable
private enum class WirePairSuccessType {
  @SerialName("pair_success") PairSuccess,
}

private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
