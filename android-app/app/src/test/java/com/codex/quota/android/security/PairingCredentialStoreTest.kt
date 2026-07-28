package com.codex.quota.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCredentialStoreTest {
  @Test
  fun fixedBinaryCodecRoundTripsOnlyFingerprintAndPhoneToken() {
    val credentials =
      PairingCredentials.fromHex(
        computerFingerprintHex = "A1".repeat(32),
        phoneTokenHex = "b2".repeat(32),
      )

    val payload = PairingCredentialCodec.encode(credentials)
    val restored = PairingCredentialCodec.decode(payload)

    assertEquals(64, payload.size)
    assertEquals("a1".repeat(32), restored.computerFingerprintHex)
    assertEquals("b2".repeat(32), restored.phoneTokenHex)
  }

  @Test
  fun rejectsMalformedCredentialsAndPayloads() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingCredentials.fromHex("short", "00".repeat(32))
    }
    assertThrows(IllegalArgumentException::class.java) {
      PairingCredentials.fromHex("00".repeat(32), "zz".repeat(32))
    }
    assertThrows(IllegalArgumentException::class.java) {
      PairingCredentialCodec.decode(ByteArray(63))
    }
  }
}
