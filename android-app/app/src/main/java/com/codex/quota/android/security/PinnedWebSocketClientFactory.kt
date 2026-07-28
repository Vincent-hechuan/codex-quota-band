package com.codex.quota.android.security

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

object PinnedWebSocketClientFactory {
  fun create(identity: PinnedComputerIdentity): OkHttpClient {
    val trustManager = PinnedPublicKeyTrustManager(identity)
    val sslContext =
      SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
      }
    return OkHttpClient.Builder()
      .sslSocketFactory(sslContext.socketFactory, trustManager)
      .hostnameVerifier { _, session ->
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        leaf != null && identity.matches(leaf)
      }
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .pingInterval(15, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }
}
