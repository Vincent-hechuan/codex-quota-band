package com.codex.quota.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingDiscoveryWireContractTest {
  @Test
  fun decodesDiscoveryWithoutExposingThePairingCode() {
    val fingerprint = "a7f219c4" + "ab".repeat(28)
    val discovery =
      PairingDiscoveryWireContract.decode(
        """
        {
          "protocolVersion": 1,
          "type": "pairing_discovery",
          "computerFingerprint": "$fingerprint",
          "endpoints": ["wss://192.168.1.42:17322/pair"],
          "expiresAtMs": 1784880300000
        }
        """.trimIndent(),
      )

    assertEquals("A7F2-19C4", discovery.securityCode)
    assertEquals("wss://192.168.1.42:17322/pair", discovery.endpoints.single().toString())
  }

  @Test
  fun rejectsDiscoveryThatContainsASecretField() {
    val payload =
      """{"protocolVersion":1,"type":"pairing_discovery","computerFingerprint":"${"ab".repeat(32)}","endpoints":["wss://192.168.1.42:17322/pair"],"expiresAtMs":1784880300000,"code":"123456"}"""

    assertThrows(Exception::class.java) { PairingDiscoveryWireContract.decode(payload) }
  }
}
