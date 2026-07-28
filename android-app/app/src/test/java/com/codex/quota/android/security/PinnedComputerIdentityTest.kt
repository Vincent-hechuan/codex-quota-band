package com.codex.quota.android.security

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedComputerIdentityTest {
  @Test
  fun matchesOnlyThePinnedSubjectPublicKeyInformation() {
    val publicKeyDer = "windows-public-key".toByteArray()
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyDer).toHex()
    val identity = PinnedComputerIdentity.fromHex(fingerprint.uppercase())

    assertEquals(fingerprint, identity.fingerprintHex)
    assertTrue(identity.matchesPublicKeyDer(publicKeyDer))
    assertFalse(identity.matchesPublicKeyDer("other-public-key".toByteArray()))
  }

  @Test
  fun rejectsMalformedOrTruncatedFingerprints() {
    assertThrows(IllegalArgumentException::class.java) {
      PinnedComputerIdentity.fromHex("abc")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PinnedComputerIdentity.fromHex("z".repeat(64))
    }
  }

  private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
