package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.SyncClientHelloWireContract
import com.codex.quota.android.protocol.SyncRefreshRequestWireContract
import com.codex.quota.android.notifications.TaskAlertCoordinator
import com.codex.quota.android.security.PairingCredentials
import com.codex.quota.android.security.PinnedComputerIdentity
import com.codex.quota.android.security.PinnedWebSocketClientFactory
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

class SyncWebSocketClient(
  private val scope: CoroutineScope,
  private val repository: RuntimeStateRepository,
  private val taskAlerts: TaskAlertCoordinator? = null,
) {
  private var connectionJob: Job? = null
  private var activeWebSocket: WebSocket? = null
  private var activeSession: SyncStreamSession? = null
  private var savedConnection: SavedConnection? = null
  private var freshnessWatchdogJob: Job? = null

  fun start(endpoint: URI, credentials: PairingCredentials, clientInstanceId: String) {
    ConnectionEndpointValidator.requireValid(endpoint)
    savedConnection = SavedConnection(endpoint, credentials, clientInstanceId)
    startFreshnessWatchdog()
    reconnect(savedConnection!!)
  }

  /** Requests a fresh upstream quota check; reconnects only before negotiation is ready. */
  fun refresh(): Boolean {
    val connection = savedConnection ?: return false
    if (requestQuotaRefreshIfReady()) return true
    reconnect(connection)
    return true
  }

  private fun reconnect(connection: SavedConnection) {
    closeActiveConnection()
    connectionJob =
      scope.launch {
        var attempt = 0
        while (isActive) {
          try {
            connectOnce(connection.endpoint, connection.credentials, connection.clientInstanceId)
            attempt = 0
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            repository.markTransportDisconnected()
          }
          delay(reconnectDelayMs(attempt++))
        }
      }
  }

  fun stop() {
    freshnessWatchdogJob?.cancel()
    freshnessWatchdogJob = null
    closeActiveConnection()
  }

  private fun closeActiveConnection() {
    connectionJob?.cancel()
    connectionJob = null
    activeWebSocket?.cancel()
    activeWebSocket = null
    activeSession = null
    repository.markTransportDisconnected()
  }

  private fun startFreshnessWatchdog() {
    freshnessWatchdogJob?.cancel()
    freshnessWatchdogJob =
      scope.launch {
        val automaticRefreshSchedule = AutomaticRefreshSchedule()
        while (isActive) {
          delay(FRESHNESS_REASSESSMENT_INTERVAL_MS)
          repository.reassessQuotaFreshness()
          automaticRefreshSchedule.onWatchdogTick(::requestQuotaRefreshIfReady)
        }
      }
  }

  private fun requestQuotaRefreshIfReady(): Boolean {
    val socket = activeWebSocket ?: return false
    val connectionId = activeSession?.currentConnectionId() ?: return false
    return socket.send(SyncRefreshRequestWireContract.encode(connectionId))
  }

  private suspend fun connectOnce(
    endpoint: URI,
    credentials: PairingCredentials,
    clientInstanceId: String,
  ) {
    val identity = PinnedComputerIdentity.fromHex(credentials.computerFingerprintHex)
    val client = PinnedWebSocketClientFactory.create(identity)
    val session =
      SyncStreamSession(repository) { snapshot, reconnect ->
        taskAlerts?.ingest(snapshot, reconnect)
      }
    activeSession = session
    try {
      suspendCancellableCoroutine { continuation ->
        val request =
          Request.Builder()
            .url(endpoint.toString())
            .header("Authorization", "Bearer ${credentials.phoneTokenHex}")
            .build()
        val listener =
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              activeWebSocket = webSocket
              val hello = SyncClientHelloWireContract.encode(clientInstanceId)
              if (!webSocket.send(hello)) webSocket.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              runCatching { session.ingest(text) }
                .onFailure { webSocket.close(1002, "invalid sync frame") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              session.disconnect()
              activeWebSocket = null
              activeSession = null
              if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              session.disconnect()
              activeWebSocket = null
              activeSession = null
              if (continuation.isActive) continuation.resume(Unit)
            }
          }
        val webSocket = client.newWebSocket(request, listener)
        activeWebSocket = webSocket
        continuation.invokeOnCancellation { webSocket.cancel() }
      }
    } finally {
      session.disconnect()
      activeWebSocket = null
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  internal companion object {
    internal const val FRESHNESS_REASSESSMENT_INTERVAL_MS = 5_000L
    private const val AUTOMATIC_REFRESH_INTERVAL_MS = 45_000L

    fun automaticRefreshIntervalMs(): Long = AUTOMATIC_REFRESH_INTERVAL_MS

    fun reconnectDelayMs(attempt: Int): Long =
      when (attempt.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 5_000L
        3 -> 10_000L
        else -> 30_000L
      }
  }

  private data class SavedConnection(
    val endpoint: URI,
    val credentials: PairingCredentials,
    val clientInstanceId: String,
  )
}

/**
 * Keeps the automatic quota refresh due until the authenticated request has actually entered the
 * current WebSocket. A pending negotiation or reconnect must not buy another 45 seconds of age.
 */
internal class AutomaticRefreshSchedule(
  private val reassessmentIntervalMs: Long = SyncWebSocketClient.FRESHNESS_REASSESSMENT_INTERVAL_MS,
  private val refreshIntervalMs: Long = SyncWebSocketClient.automaticRefreshIntervalMs(),
) {
  private var elapsedSinceRefreshMs = 0L

  fun onWatchdogTick(requestRefresh: () -> Boolean): Boolean {
    elapsedSinceRefreshMs += reassessmentIntervalMs
    if (elapsedSinceRefreshMs < refreshIntervalMs) return false
    if (requestRefresh()) elapsedSinceRefreshMs = 0L
    return true
  }
}

private object ConnectionEndpointValidator {
  fun requireValid(endpoint: URI) {
    com.codex.quota.android.security.ConnectionIdentityStore.requireValidSyncEndpoint(endpoint)
  }
}
