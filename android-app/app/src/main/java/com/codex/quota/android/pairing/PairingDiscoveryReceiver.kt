package com.codex.quota.android.pairing

import com.codex.quota.android.protocol.PairingDiscovery
import com.codex.quota.android.protocol.PairingDiscoveryWireContract
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class PairingDiscoveryCollector(
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val byFingerprint = linkedMapOf<String, PairingDiscovery>()

  @Synchronized
  fun accept(payload: String): List<PairingDiscovery> {
    val now = clock()
    val discovery = runCatching { PairingDiscoveryWireContract.decode(payload) }.getOrNull()
    if (discovery != null && discovery.expiresAtMs >= now) {
      val current = byFingerprint[discovery.computerFingerprintHex]
      if (current == null || discovery.expiresAtMs > current.expiresAtMs) {
        byFingerprint[discovery.computerFingerprintHex] = discovery
      }
    }
    byFingerprint.entries.removeAll { it.value.expiresAtMs < now }
    return byFingerprint.values.sortedByDescending(PairingDiscovery::expiresAtMs)
  }
}

class PairingDiscoveryReceiver(
  private val onDiscoveriesChanged: (List<PairingDiscovery>) -> Unit,
) : AutoCloseable {
  private val running = AtomicBoolean(false)
  private var socket: DatagramSocket? = null
  private var thread: Thread? = null

  fun start() {
    if (!running.compareAndSet(false, true)) return
    thread =
      Thread({ receiveLoop() }, "pairing-discovery").apply {
        isDaemon = true
        start()
      }
  }

  private fun receiveLoop() {
    val collector = PairingDiscoveryCollector()
    val receiver =
      runCatching {
          DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DISCOVERY_PORT))
            soTimeout = 1_000
          }
        }
        .getOrElse {
          running.set(false)
          return
        }
    socket = receiver
    val buffer = ByteArray(MAX_PACKET_BYTES)
    while (running.get()) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        receiver.receive(packet)
        val source = packet.address
        if (!source.isSiteLocalAddress && !source.isLinkLocalAddress) continue
        val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length).toString(Charsets.UTF_8)
        onDiscoveriesChanged(collector.accept(payload))
      } catch (_: SocketTimeoutException) {
        // Re-check the close flag.
      } catch (_: Exception) {
        if (running.get()) continue
      }
    }
    receiver.close()
  }

  override fun close() {
    running.set(false)
    socket?.close()
    thread?.interrupt()
  }

  private companion object {
    const val DISCOVERY_PORT = 37_231
    const val MAX_PACKET_BYTES = 4 * 1024
  }
}
