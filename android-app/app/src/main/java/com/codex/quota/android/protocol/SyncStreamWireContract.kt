package com.codex.quota.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface SyncStreamFrame {
  val connectionId: String

  data class ServerHello(
    override val connectionId: String,
    val quotaVersion: Int,
    val heartbeatIntervalMs: Int,
  ) : SyncStreamFrame

  data class Snapshot(
    override val connectionId: String,
    val sequence: Long,
    val generatedAtMs: Long,
    val quota: QuotaSnapshot,
    val tasks: TaskSnapshot,
  ) : SyncStreamFrame

  data class Heartbeat(
    override val connectionId: String,
    val sequence: Long,
    val generatedAtMs: Long,
  ) : SyncStreamFrame
}

object SyncStreamWireContract {
  private val json =
    Json {
      ignoreUnknownKeys = false
      explicitNulls = true
    }

  /**
   * Decode a frame using the version already negotiated by Server Hello. A snapshot without
   * that value is rejected before its nested quota payload is parsed.
   */
  fun decode(payload: String, negotiatedQuotaVersion: Int? = null): SyncStreamFrame {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "sync frame is too large"
    }
    val root = json.parseToJsonElement(payload).jsonObject
    return when (root["type"]?.jsonPrimitive?.content) {
      "server_hello" -> decodeServerHello(payload)
      "snapshot" -> decodeSnapshot(payload, negotiatedQuotaVersion)
      "heartbeat" -> decodeHeartbeat(payload)
      else -> throw IllegalArgumentException("unsupported sync frame")
    }
  }

  private fun decodeServerHello(payload: String): SyncStreamFrame.ServerHello {
    val wire = json.decodeFromString<WireServerHello>(payload)
    require(wire.transportVersion == TRANSPORT_VERSION) { "unsupported transport protocol" }
    require(wire.quotaVersion in SUPPORTED_QUOTA_VERSIONS && wire.taskVersion == TASK_VERSION) {
      "incompatible snapshot protocol"
    }
    require(wire.heartbeatIntervalMs in MIN_HEARTBEAT_MS..MAX_HEARTBEAT_MS) {
      "invalid heartbeat interval"
    }
    requireConnectionId(wire.connectionId)
    return SyncStreamFrame.ServerHello(wire.connectionId, wire.quotaVersion, wire.heartbeatIntervalMs)
  }

  private fun decodeSnapshot(
    payload: String,
    negotiatedQuotaVersion: Int?,
  ): SyncStreamFrame.Snapshot {
    val negotiatedVersion = negotiatedQuotaVersion ?: throw IllegalArgumentException("snapshot received before quota negotiation")
    require(negotiatedVersion in SUPPORTED_QUOTA_VERSIONS) {
      "snapshot received before quota negotiation"
    }
    val wire = json.decodeFromString<WireSnapshotFrame>(payload)
    require(wire.transportVersion == TRANSPORT_VERSION) { "unsupported transport protocol" }
    require(wire.sequence >= 0 && wire.generatedAtMs >= 0) { "invalid sync metadata" }
    requireConnectionId(wire.connectionId)
    val declaredQuotaVersion = wire.quota["protocolVersion"]?.jsonPrimitive?.content?.toIntOrNull()
    require(declaredQuotaVersion == negotiatedVersion) {
      "quota snapshot does not match negotiation"
    }
    val quota = QuotaWireContract.decode(wire.quota.toString(), negotiatedVersion)
    val tasks = TaskWireContract.decode(wire.tasks.toString())
    require(quota.generatedAtMs <= wire.generatedAtMs && tasks.generatedAtMs <= wire.generatedAtMs) {
      "nested snapshot is newer than its frame"
    }
    return SyncStreamFrame.Snapshot(
      connectionId = wire.connectionId,
      sequence = wire.sequence,
      generatedAtMs = wire.generatedAtMs,
      quota = quota,
      tasks = tasks,
    )
  }

  private fun decodeHeartbeat(payload: String): SyncStreamFrame.Heartbeat {
    val wire = json.decodeFromString<WireHeartbeat>(payload)
    require(wire.transportVersion == TRANSPORT_VERSION) { "unsupported transport protocol" }
    require(wire.sequence >= 0 && wire.generatedAtMs >= 0) { "invalid heartbeat metadata" }
    requireConnectionId(wire.connectionId)
    return SyncStreamFrame.Heartbeat(wire.connectionId, wire.sequence, wire.generatedAtMs)
  }

  private fun requireConnectionId(value: String) {
    require(value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      "invalid connection identity"
    }
  }

  private const val TRANSPORT_VERSION = 1
  private const val TASK_VERSION = 1
  private const val MIN_HEARTBEAT_MS = 5_000
  private const val MAX_HEARTBEAT_MS = 60_000
  private const val MAX_PAYLOAD_BYTES = 128 * 1024
  private val SUPPORTED_QUOTA_VERSIONS = setOf(1, 2, 3)
}

@Serializable
private data class WireServerHello(
  val type: WireServerHelloType,
  val transportVersion: Int,
  val connectionId: String,
  val quotaVersion: Int,
  val taskVersion: Int,
  val heartbeatIntervalMs: Int,
)

@Serializable
private data class WireSnapshotFrame(
  val type: WireSnapshotType,
  val transportVersion: Int,
  val connectionId: String,
  val sequence: Long,
  val generatedAtMs: Long,
  val quota: JsonObject,
  val tasks: JsonObject,
)

@Serializable
private data class WireHeartbeat(
  val type: WireHeartbeatType,
  val transportVersion: Int,
  val connectionId: String,
  val sequence: Long,
  val generatedAtMs: Long,
)

@Serializable
private enum class WireServerHelloType {
  @SerialName("server_hello") ServerHello,
}

@Serializable
private enum class WireSnapshotType {
  @SerialName("snapshot") Snapshot,
}

@Serializable
private enum class WireHeartbeatType {
  @SerialName("heartbeat") Heartbeat,
}
