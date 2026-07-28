package com.codex.quota.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PairingCredentials private constructor(
  val computerFingerprintHex: String,
  val phoneTokenHex: String,
) {
  companion object {
    fun fromHex(computerFingerprintHex: String, phoneTokenHex: String): PairingCredentials {
      return PairingCredentials(
        computerFingerprintHex = normalizeSha256Hex(computerFingerprintHex),
        phoneTokenHex = normalizeSha256Hex(phoneTokenHex),
      )
    }
  }
}

internal object PairingCredentialCodec {
  fun encode(credentials: PairingCredentials): ByteArray =
    hexToBytes(credentials.computerFingerprintHex) + hexToBytes(credentials.phoneTokenHex)

  fun decode(payload: ByteArray): PairingCredentials {
    require(payload.size == PAYLOAD_BYTES) { "invalid pairing credential payload" }
    return PairingCredentials.fromHex(
      computerFingerprintHex = payload.copyOfRange(0, SHA256_BYTES).toHex(),
      phoneTokenHex = payload.copyOfRange(SHA256_BYTES, PAYLOAD_BYTES).toHex(),
    )
  }

  private const val SHA256_BYTES = 32
  private const val PAYLOAD_BYTES = SHA256_BYTES * 2
}

class PairingCredentialStore(context: Context) {
  private val preferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun save(credentials: PairingCredentials) {
    val plaintext = PairingCredentialCodec.encode(credentials)
    try {
      val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
      val ciphertext = cipher.doFinal(plaintext)
      val envelope =
        ByteBuffer.allocate(1 + 1 + cipher.iv.size + ciphertext.size)
          .put(ENVELOPE_VERSION)
          .put(cipher.iv.size.toByte())
          .put(cipher.iv)
          .put(ciphertext)
          .array()
      preferences.edit {
        putString(KEY_CREDENTIAL_ENVELOPE, Base64.encodeToString(envelope, Base64.NO_WRAP))
      }
    } finally {
      plaintext.fill(0)
    }
  }

  fun load(): PairingCredentials? {
    val encoded = preferences.getString(KEY_CREDENTIAL_ENVELOPE, null) ?: return null
    return runCatching {
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        require(envelope.size > MIN_ENVELOPE_BYTES) { "invalid encrypted credentials" }
        val buffer = ByteBuffer.wrap(envelope)
        require(buffer.get() == ENVELOPE_VERSION) { "unsupported credential envelope" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "invalid credential IV" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
          PairingCredentialCodec.decode(plaintext)
        } finally {
          plaintext.fill(0)
          ciphertext.fill(0)
        }
      }
      .getOrElse {
        clear()
        null
      }
  }

  fun clear() {
    preferences.edit { remove(KEY_CREDENTIAL_ENVELOPE) }
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val PREFERENCES_NAME = "pairing-credentials"
    const val KEY_CREDENTIAL_ENVELOPE = "encrypted-envelope"
    const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val KEY_ALIAS = "codex-quota-pairing-v1"
    const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val MIN_ENVELOPE_BYTES = 1 + 1 + 12 + 16
    const val ENVELOPE_VERSION: Byte = 1
  }
}

private fun normalizeSha256Hex(value: String): String {
  require(value.length == 64 && value.all(Char::isCredentialHexDigit)) {
    "invalid 256-bit credential"
  }
  return value.lowercase()
}

private fun hexToBytes(value: String): ByteArray =
  ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }

private fun ByteArray.toHex(): String =
  joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun Char.isCredentialHexDigit(): Boolean =
  this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
