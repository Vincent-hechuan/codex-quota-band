package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.PairingOffer
import com.codex.quota.android.protocol.PairingSessionWireContract
import com.codex.quota.android.security.PairingCredentials
import com.codex.quota.android.security.PinnedComputerIdentity
import com.codex.quota.android.security.PinnedWebSocketClientFactory
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class PairingResult(
  val credentials: PairingCredentials,
  val syncEndpoint: URI,
)

class PairingClient(
  private val clock: () -> Long = System::currentTimeMillis,
) {
  suspend fun pair(offer: PairingOffer, clientInstanceId: String): PairingResult {
    require(clock() <= offer.expiresAtMs) { "pairing offer expired" }
    val identity = PinnedComputerIdentity.fromHex(offer.computerFingerprintHex)
    var lastFailure: Throwable = IllegalStateException("no pairing endpoint succeeded")
    for (endpoint in offer.endpoints) {
      try {
        val phoneToken = pairEndpoint(identity, endpoint, offer.code, clientInstanceId)
        return PairingResult(
          credentials = PairingCredentials.fromHex(offer.computerFingerprintHex, phoneToken),
          syncEndpoint = endpoint.toSyncEndpoint(),
        )
      } catch (failure: Throwable) {
        lastFailure = failure
      }
    }
    throw lastFailure
  }

  private suspend fun pairEndpoint(
    identity: PinnedComputerIdentity,
    endpoint: URI,
    code: String,
    clientInstanceId: String,
  ): String {
    val client = PinnedWebSocketClientFactory.create(identity)
    return try {
      withTimeout(PAIRING_TIMEOUT_MS) {
      suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(endpoint.toString()).build()
        val payload = PairingSessionWireContract.encodeRequest(code, clientInstanceId)
        val listener =
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              if (!webSocket.send(payload) && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("pairing send failed"))
                webSocket.cancel()
              }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              runCatching { PairingSessionWireContract.decodeSuccess(text) }
                .onSuccess { token ->
                  if (continuation.isActive) continuation.resume(token)
                  webSocket.close(1000, null)
                }
                .onFailure { failure ->
                  if (continuation.isActive) continuation.resumeWithException(failure)
                  webSocket.close(1002, "invalid pairing response")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              if (continuation.isActive) continuation.resumeWithException(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("pairing closed"))
              }
            }
          }
        val webSocket = client.newWebSocket(request, listener)
        continuation.invokeOnCancellation {
          webSocket.cancel()
        }
      }
      }
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  private fun URI.toSyncEndpoint(): URI = URI(scheme, null, host, port, "/sync", null, null)

  private companion object {
    const val PAIRING_TIMEOUT_MS = 10_000L
  }
}
