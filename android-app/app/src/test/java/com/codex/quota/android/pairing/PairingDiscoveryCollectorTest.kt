package com.codex.quota.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingDiscoveryCollectorTest {
  @Test
  fun keepsOnlyTheNewestUnexpiredOfferForEachComputer() {
    val collector = PairingDiscoveryCollector(clock = { 1_000 })
    val fingerprint = "ab".repeat(32)

    assertTrue(collector.accept(discoveryJson(fingerprint, expiresAtMs = 900)).isEmpty())
    collector.accept(discoveryJson(fingerprint, expiresAtMs = 2_000))
    val visible = collector.accept(discoveryJson(fingerprint, expiresAtMs = 1_500))

    assertEquals(1, visible.size)
    assertEquals(2_000, visible.single().expiresAtMs)
  }

  private fun discoveryJson(fingerprint: String, expiresAtMs: Long): String =
    """{"protocolVersion":1,"type":"pairing_discovery","computerFingerprint":"$fingerprint","endpoints":["wss://192.168.1.42:17322/pair"],"expiresAtMs":$expiresAtMs}"""
}
