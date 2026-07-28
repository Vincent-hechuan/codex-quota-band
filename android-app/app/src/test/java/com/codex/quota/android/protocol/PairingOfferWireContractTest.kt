package com.codex.quota.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingOfferWireContractTest {
  @Test
  fun decodesOnlyPinnedTlsEndpointsOnPrivateNumericAddresses() {
    val offer = PairingOfferWireContract.decode(validOffer())

    assertEquals("ab".repeat(32), offer.computerFingerprintHex)
    assertEquals("wss://192.168.1.42:17322/pair", offer.endpoints.single().toString())
    assertEquals("123456", offer.code)
  }

  @Test
  fun rejectsPublicAddressesPlaintextAndUnknownSecretFields() {
    val publicAddress = validOffer().replace("192.168.1.42", "8.8.8.8")
    val plaintext = validOffer().replace("wss://", "ws://")
    val privateToken =
      validOffer().replace("\"code\": \"123456\",", "\"code\": \"123456\", \"token\": \"secret\",")

    assertThrows(IllegalArgumentException::class.java) { PairingOfferWireContract.decode(publicAddress) }
    assertThrows(IllegalArgumentException::class.java) { PairingOfferWireContract.decode(plaintext) }
    assertThrows(Exception::class.java) { PairingOfferWireContract.decode(privateToken) }
  }

  @Test
  fun rejectsDomainsCredentialsPathsAndDuplicateEndpoints() {
    val domain = validOffer().replace("192.168.1.42", "computer.local")
    val credentials = validOffer().replace("wss://", "wss://user@")
    val path = validOffer().replace("/pair", "/pair/extra")
    val duplicate =
      validOffer().replace(
        "\"wss://192.168.1.42:17322/pair\"",
        "\"wss://192.168.1.42:17322/pair\", \"wss://192.168.1.42:17322/pair\"",
      )

    for (payload in listOf(domain, credentials, path, duplicate)) {
      assertThrows(IllegalArgumentException::class.java) { PairingOfferWireContract.decode(payload) }
    }
  }

  private fun validOffer(): String =
    """
    {
      "protocolVersion": 1,
      "type": "pairing_offer",
      "computerFingerprint": "${"ab".repeat(32)}",
      "endpoints": ["wss://192.168.1.42:17322/pair"],
      "code": "123456",
      "expiresAtMs": 1784880300000
    }
    """.trimIndent()
}
