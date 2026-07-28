package com.codex.quota.android.protocol

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class PairingOffer(
  val computerFingerprintHex: String,
  val endpoints: List<URI>,
  val code: String,
  val expiresAtMs: Long,
)

object PairingOfferWireContract {
  private val json =
    Json {
      ignoreUnknownKeys = false
      explicitNulls = true
    }

  fun decode(payload: String): PairingOffer {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "pairing offer is too large"
    }
    val wire = json.decodeFromString<WirePairingOffer>(payload)
    require(wire.protocolVersion == PROTOCOL_VERSION) { "unsupported pairing protocol" }
    require(
      wire.computerFingerprint.length == 64 &&
        wire.computerFingerprint.all(Char::isLowerHexDigit),
    ) {
      "invalid computer fingerprint"
    }
    require(wire.code.length == 6 && wire.code.all(Char::isDigit)) { "invalid pairing code" }
    require(wire.expiresAtMs >= 0) { "invalid pairing expiry" }
    require(wire.endpoints.size in 1..MAX_ENDPOINTS && wire.endpoints.toSet().size == wire.endpoints.size) {
      "invalid pairing endpoints"
    }
    val endpoints = wire.endpoints.map(::parsePrivatePairingEndpoint)
    return PairingOffer(
      computerFingerprintHex = wire.computerFingerprint,
      endpoints = endpoints,
      code = wire.code,
      expiresAtMs = wire.expiresAtMs,
    )
  }

  private fun parsePrivatePairingEndpoint(value: String): URI {
    require(value.length <= MAX_ENDPOINT_LENGTH) { "invalid pairing endpoint" }
    val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("invalid pairing endpoint", it) }
    require(
      uri.scheme == "wss" &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        uri.rawPath == "/pair" &&
        uri.port in 1..65_535 &&
        isPrivateIpv4(uri.host),
    ) {
      "invalid pairing endpoint"
    }
    return uri
  }

  private fun isPrivateIpv4(host: String?): Boolean {
    val octets = host?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
      (octets[0] == 172 && octets[1] in 16..31) ||
      (octets[0] == 192 && octets[1] == 168) ||
      (octets[0] == 169 && octets[1] == 254)
  }

  private const val PROTOCOL_VERSION = 1
  private const val MAX_ENDPOINTS = 8
  private const val MAX_ENDPOINT_LENGTH = 128
  private const val MAX_PAYLOAD_BYTES = 4 * 1024
}

@Serializable
private data class WirePairingOffer(
  val protocolVersion: Int,
  val type: WirePairingOfferType,
  val computerFingerprint: String,
  val endpoints: List<String>,
  val code: String,
  val expiresAtMs: Long,
)

@Serializable
private enum class WirePairingOfferType {
  @SerialName("pairing_offer") PairingOffer,
}

private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
