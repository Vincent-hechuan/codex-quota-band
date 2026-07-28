package com.codex.quota.android.security

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinnedComputerIdentity private constructor(
  private val fingerprint: ByteArray,
) {
  val fingerprintHex: String
    get() = fingerprint.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

  fun matches(certificate: X509Certificate): Boolean =
    matchesPublicKeyDer(certificate.publicKey.encoded)

  internal fun matchesPublicKeyDer(publicKeyDer: ByteArray): Boolean {
    val candidate = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
    return MessageDigest.isEqual(fingerprint, candidate)
  }

  companion object {
    fun fromHex(value: String): PinnedComputerIdentity {
      require(value.length == SHA256_HEX_LENGTH && value.all(Char::isHexDigit)) {
        "invalid computer fingerprint"
      }
      val bytes =
        ByteArray(SHA256_BYTE_LENGTH) { index ->
          value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
      return PinnedComputerIdentity(bytes)
    }

    private const val SHA256_BYTE_LENGTH = 32
    private const val SHA256_HEX_LENGTH = SHA256_BYTE_LENGTH * 2
  }
}

// The pinned Windows public key is the trust anchor; public CAs are intentionally not accepted.
@SuppressLint("CustomX509TrustManager")
class PinnedPublicKeyTrustManager(
  private val identity: PinnedComputerIdentity,
) : X509TrustManager {
  override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
    throw CertificateException("client certificates are not accepted")
  }

  override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
    val leaf = chain?.firstOrNull() ?: throw CertificateException("missing server certificate")
    leaf.checkValidity()
    if (!identity.matches(leaf)) throw CertificateException("computer identity mismatch")
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
