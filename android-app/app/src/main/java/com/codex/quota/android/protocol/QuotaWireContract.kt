package com.codex.quota.android.protocol

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class QuotaSourceStatus {
  Ok,
  Partial,
  Unavailable,
  Paused,
}

enum class QuotaWindowStatus {
  Current,
  PendingSync,
  Unknown,
}

enum class ResetInventoryStatus {
  Cached,
  CachedDerived,
  Missing,
  Unavailable,
}

enum class ComputerLinkStatus {
  Online,
  Offline,
  Paused,
}

enum class CodexLinkStatus {
  Ok,
  Unavailable,
  Stale,
  FormatChanged,
}

enum class UpstreamFreshnessStatus {
  Current,
  Cached,
  Unavailable,
}

data class UpstreamDatasetFreshness(
  val status: UpstreamFreshnessStatus,
  val lastAttemptAtMs: Long?,
  val lastSuccessAtMs: Long?,
)

data class UpstreamFreshness(
  val usage: UpstreamDatasetFreshness,
  val resetInventory: UpstreamDatasetFreshness,
)

data class QuotaWindow(
  val id: String,
  val name: String,
  val windowMinutes: Int,
  val remainingPercent: Int?,
  val resetsAtMs: Long,
  val status: QuotaWindowStatus,
)

data class ResetInventoryItem(
  val id: String,
  val title: String,
  val expiresAtMs: Long,
  val grantedAtMs: Long? = null,
)

data class ResetInventorySnapshot(
  val status: ResetInventoryStatus,
  val availableCount: Int?,
  val cachedAtMs: Long?,
  val items: List<ResetInventoryItem>,
)

data class QuotaSnapshot(
  val generatedAtMs: Long,
  val sourceStatus: QuotaSourceStatus,
  val limitsCollectedAtMs: Long?,
  val windows: List<QuotaWindow>,
  val resetInventory: ResetInventorySnapshot,
  val computerLink: ComputerLinkStatus,
  val codexLink: CodexLinkStatus,
  val upstreamFreshness: UpstreamFreshness? = null,
)

object QuotaWireContract {
  private val json =
    Json {
      ignoreUnknownKeys = false
      explicitNulls = true
    }

  /** Decodes a standalone quota payload, selecting its declared protocol version. */
  fun decode(payload: String): QuotaSnapshot {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "quota snapshot is too large"
    }
    val declaredVersion =
      json.parseToJsonElement(payload).jsonObject["protocolVersion"]?.jsonPrimitive?.int
        ?: throw IllegalArgumentException("missing quota protocol")
    return decode(payload, declaredVersion)
  }

  /** Decodes only the version selected by the enclosing sync Server Hello. */
  fun decode(payload: String, expectedVersion: Int): QuotaSnapshot {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "quota snapshot is too large"
    }
    return when (expectedVersion) {
      1 -> decodeV1(payload)
      2 -> decodeV2(payload)
      3 -> decodeV3(payload)
      else -> throw IllegalArgumentException("unsupported quota protocol")
    }
  }

  fun encode(snapshot: QuotaSnapshot): String =
    json.encodeToString(
      QuotaWireSnapshotV1(
        protocolVersion = 1,
        generatedAt = formatTimestamp(snapshot.generatedAtMs),
        sourceStatus = snapshot.sourceStatus.toWire(),
        limitsCollectedAt = snapshot.limitsCollectedAtMs?.let(::formatTimestamp),
        windows = snapshot.windows.map { window ->
          QuotaWireWindow(
            id = window.id,
            name = window.name,
            windowMinutes = window.windowMinutes,
            remainingPercent = window.remainingPercent,
            resetsAt = formatTimestamp(window.resetsAtMs),
            status = window.status.toWire(),
          )
        },
        resetInventory = QuotaWireResetInventoryV1(
          status = snapshot.resetInventory.status.toWire(),
          availableCount = snapshot.resetInventory.availableCount,
          cachedAt = snapshot.resetInventory.cachedAtMs?.let(::formatTimestamp),
          items = snapshot.resetInventory.items.map { item ->
            QuotaWireResetItemV1(
              id = item.id,
              title = item.title,
              status = WireResetItemStatus.Available,
              expiresAt = formatTimestamp(item.expiresAtMs),
            )
          },
        ),
        link = QuotaWireLink(
          computer = snapshot.computerLink.toWire(),
          codex = snapshot.codexLink.toWire(),
        ),
      ),
    )

  private fun decodeV1(payload: String): QuotaSnapshot {
    val wire = json.decodeFromString<QuotaWireSnapshotV1>(payload)
    require(wire.protocolVersion == 1) { "quota protocol does not match negotiation" }
    val resetItems = wire.resetInventory.items.map { item ->
      require(validText(item.id, 256)) { "invalid reset identity" }
      require(validText(item.title, 128)) { "invalid reset title" }
      ResetInventoryItem(
        id = item.id,
        title = item.title,
        expiresAtMs = parseTimestamp(item.expiresAt),
      )
    }
    return buildSnapshot(
      generatedAt = wire.generatedAt,
      sourceStatus = wire.sourceStatus,
      limitsCollectedAt = wire.limitsCollectedAt,
      windows = wire.windows,
      resetInventoryStatus = wire.resetInventory.status,
      availableCount = wire.resetInventory.availableCount,
      cachedAt = wire.resetInventory.cachedAt,
      resetItems = resetItems,
      link = wire.link,
      upstreamFreshness = null,
    )
  }

  private fun decodeV2(payload: String): QuotaSnapshot {
    val wire = json.decodeFromString<QuotaWireSnapshotV2>(payload)
    require(wire.protocolVersion == 2) { "quota protocol does not match negotiation" }
    val resetItems = wire.resetInventory.items.map { item ->
      ResetInventoryItem(
        id = "",
        title = "",
        grantedAtMs = item.grantedAt?.let(::parseTimestamp),
        expiresAtMs = parseTimestamp(item.expiresAt),
      )
    }
    return buildSnapshot(
      generatedAt = wire.generatedAt,
      sourceStatus = wire.sourceStatus,
      limitsCollectedAt = wire.limitsCollectedAt,
      windows = wire.windows,
      resetInventoryStatus = wire.resetInventory.status,
      availableCount = wire.resetInventory.availableCount,
      cachedAt = wire.resetInventory.cachedAt,
      resetItems = resetItems,
      link = wire.link,
      upstreamFreshness = null,
    )
  }

  private fun decodeV3(payload: String): QuotaSnapshot {
    val wire = json.decodeFromString<QuotaWireSnapshotV3>(payload)
    require(wire.protocolVersion == 3) { "quota protocol does not match negotiation" }
    val resetItems = wire.resetInventory.items.map { item ->
      ResetInventoryItem(
        id = "",
        title = "",
        grantedAtMs = item.grantedAt?.let(::parseTimestamp),
        expiresAtMs = parseTimestamp(item.expiresAt),
      )
    }
    return buildSnapshot(
      generatedAt = wire.generatedAt,
      sourceStatus = wire.sourceStatus,
      limitsCollectedAt = wire.limitsCollectedAt,
      windows = wire.windows,
      resetInventoryStatus = wire.resetInventory.status,
      availableCount = wire.resetInventory.availableCount,
      cachedAt = wire.resetInventory.cachedAt,
      resetItems = resetItems,
      link = wire.link,
      upstreamFreshness =
        UpstreamFreshness(
          usage = wire.upstreamFreshness.usage.toDomain(::parseTimestamp),
          resetInventory = wire.upstreamFreshness.resetInventory.toDomain(::parseTimestamp),
        ),
    )
  }

  private fun buildSnapshot(
    generatedAt: String,
    sourceStatus: WireQuotaSourceStatus,
    limitsCollectedAt: String?,
    windows: List<QuotaWireWindow>,
    resetInventoryStatus: WireResetInventoryStatus,
    availableCount: Int?,
    cachedAt: String?,
    resetItems: List<ResetInventoryItem>,
    link: QuotaWireLink,
    upstreamFreshness: UpstreamFreshness?,
  ): QuotaSnapshot {
    val windowIds = HashSet<String>()
    val decodedWindows = windows.map { window ->
      require(validText(window.id, 128) && windowIds.add(window.id)) {
        "invalid quota window identity"
      }
      require(validText(window.name, 64) && window.windowMinutes > 0) {
        "invalid quota window"
      }
      require(window.remainingPercent == null || window.remainingPercent in 0..100) {
        "invalid remaining percentage"
      }
      QuotaWindow(
        id = window.id,
        name = window.name,
        windowMinutes = window.windowMinutes,
        remainingPercent = window.remainingPercent,
        resetsAtMs = parseTimestamp(window.resetsAt),
        status = window.status.toDomain(),
      )
    }
    require(availableCount == null || availableCount >= 0) { "invalid reset count" }
    return QuotaSnapshot(
      generatedAtMs = parseTimestamp(generatedAt),
      sourceStatus = sourceStatus.toDomain(),
      limitsCollectedAtMs = limitsCollectedAt?.let(::parseTimestamp),
      windows = decodedWindows,
      resetInventory =
        ResetInventorySnapshot(
          status = resetInventoryStatus.toDomain(),
          availableCount = availableCount,
          cachedAtMs = cachedAt?.let(::parseTimestamp),
          items = resetItems,
        ),
      computerLink = link.computer.toDomain(),
      codexLink = link.codex.toDomain(),
      upstreamFreshness = upstreamFreshness,
    )
  }

  private fun parseTimestamp(value: String): Long {
    require(value.length <= MAX_TIMESTAMP_LENGTH) { "invalid timestamp" }
    val epochMs =
      runCatching {
          OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        }
        .getOrElse { throw IllegalArgumentException("invalid timestamp", it) }
    require(epochMs >= 0) { "invalid timestamp" }
    return epochMs
  }

  private fun formatTimestamp(value: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)
      .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

  private fun validText(value: String, maxCodePoints: Int): Boolean =
    value.isNotBlank() &&
      value.codePoints().count() <= maxCodePoints &&
      value.none(Char::isISOControl)

  private const val MAX_PAYLOAD_BYTES = 64 * 1024
  private const val MAX_TIMESTAMP_LENGTH = 64
}

@Serializable
private data class QuotaWireSnapshotV1(
  val protocolVersion: Int,
  val generatedAt: String,
  val sourceStatus: WireQuotaSourceStatus,
  val limitsCollectedAt: String?,
  val windows: List<QuotaWireWindow>,
  val resetInventory: QuotaWireResetInventoryV1,
  val link: QuotaWireLink,
)

@Serializable
private data class QuotaWireSnapshotV2(
  val protocolVersion: Int,
  val generatedAt: String,
  val sourceStatus: WireQuotaSourceStatus,
  val limitsCollectedAt: String?,
  val windows: List<QuotaWireWindow>,
  val resetInventory: QuotaWireResetInventoryV2,
  val link: QuotaWireLink,
)

@Serializable
private data class QuotaWireSnapshotV3(
  val protocolVersion: Int,
  val generatedAt: String,
  val sourceStatus: WireQuotaSourceStatus,
  val limitsCollectedAt: String?,
  val windows: List<QuotaWireWindow>,
  val resetInventory: QuotaWireResetInventoryV2,
  val link: QuotaWireLink,
  val upstreamFreshness: QuotaWireUpstreamFreshness,
)

@Serializable
private data class QuotaWireUpstreamFreshness(
  val usage: QuotaWireDatasetFreshness,
  val resetInventory: QuotaWireDatasetFreshness,
)

@Serializable
private data class QuotaWireDatasetFreshness(
  val status: WireUpstreamFreshnessStatus,
  val lastAttemptAt: String?,
  val lastSuccessAt: String?,
)

@Serializable
private data class QuotaWireWindow(
  val id: String,
  val name: String,
  val windowMinutes: Int,
  val remainingPercent: Int?,
  val resetsAt: String,
  val status: WireQuotaWindowStatus,
)

@Serializable
private data class QuotaWireResetInventoryV1(
  val status: WireResetInventoryStatus,
  val availableCount: Int?,
  val cachedAt: String?,
  val items: List<QuotaWireResetItemV1>,
)

@Serializable
private data class QuotaWireResetInventoryV2(
  val status: WireResetInventoryStatus,
  val availableCount: Int?,
  val cachedAt: String?,
  val items: List<QuotaWireResetItemV2>,
)

@Serializable
private data class QuotaWireResetItemV1(
  val id: String,
  val title: String,
  val status: WireResetItemStatus,
  val expiresAt: String,
)

@Serializable
private data class QuotaWireResetItemV2(
  val status: WireResetItemStatus,
  val grantedAt: String?,
  val expiresAt: String,
)

@Serializable
private data class QuotaWireLink(
  val computer: WireComputerLinkStatus,
  val codex: WireCodexLinkStatus,
)

@Serializable
private enum class WireQuotaSourceStatus {
  @SerialName("ok") Ok,
  @SerialName("partial") Partial,
  @SerialName("unavailable") Unavailable,
  @SerialName("paused") Paused,
}

@Serializable
private enum class WireQuotaWindowStatus {
  @SerialName("current") Current,
  @SerialName("pending_sync") PendingSync,
  @SerialName("unknown") Unknown,
}

@Serializable
private enum class WireResetInventoryStatus {
  @SerialName("cached") Cached,
  @SerialName("cached_derived") CachedDerived,
  @SerialName("missing") Missing,
  @SerialName("unavailable") Unavailable,
}

@Serializable
private enum class WireResetItemStatus {
  @SerialName("available") Available,
}

@Serializable
private enum class WireComputerLinkStatus {
  @SerialName("online") Online,
  @SerialName("offline") Offline,
  @SerialName("paused") Paused,
}

@Serializable
private enum class WireCodexLinkStatus {
  @SerialName("ok") Ok,
  @SerialName("unavailable") Unavailable,
  @SerialName("stale") Stale,
  @SerialName("format_changed") FormatChanged,
}

@Serializable
private enum class WireUpstreamFreshnessStatus {
  @SerialName("current") Current,
  @SerialName("cached") Cached,
  @SerialName("unavailable") Unavailable,
}

private fun WireQuotaSourceStatus.toDomain() =
  when (this) {
    WireQuotaSourceStatus.Ok -> QuotaSourceStatus.Ok
    WireQuotaSourceStatus.Partial -> QuotaSourceStatus.Partial
    WireQuotaSourceStatus.Unavailable -> QuotaSourceStatus.Unavailable
    WireQuotaSourceStatus.Paused -> QuotaSourceStatus.Paused
  }

private fun WireQuotaWindowStatus.toDomain() =
  when (this) {
    WireQuotaWindowStatus.Current -> QuotaWindowStatus.Current
    WireQuotaWindowStatus.PendingSync -> QuotaWindowStatus.PendingSync
    WireQuotaWindowStatus.Unknown -> QuotaWindowStatus.Unknown
  }

private fun WireResetInventoryStatus.toDomain() =
  when (this) {
    WireResetInventoryStatus.Cached -> ResetInventoryStatus.Cached
    WireResetInventoryStatus.CachedDerived -> ResetInventoryStatus.CachedDerived
    WireResetInventoryStatus.Missing -> ResetInventoryStatus.Missing
    WireResetInventoryStatus.Unavailable -> ResetInventoryStatus.Unavailable
  }

private fun WireComputerLinkStatus.toDomain() =
  when (this) {
    WireComputerLinkStatus.Online -> ComputerLinkStatus.Online
    WireComputerLinkStatus.Offline -> ComputerLinkStatus.Offline
    WireComputerLinkStatus.Paused -> ComputerLinkStatus.Paused
  }

private fun WireCodexLinkStatus.toDomain() =
  when (this) {
    WireCodexLinkStatus.Ok -> CodexLinkStatus.Ok
    WireCodexLinkStatus.Unavailable -> CodexLinkStatus.Unavailable
    WireCodexLinkStatus.Stale -> CodexLinkStatus.Stale
    WireCodexLinkStatus.FormatChanged -> CodexLinkStatus.FormatChanged
  }

private fun QuotaWireDatasetFreshness.toDomain(parseTimestamp: (String) -> Long) =
  UpstreamDatasetFreshness(
    status =
      when (status) {
        WireUpstreamFreshnessStatus.Current -> UpstreamFreshnessStatus.Current
        WireUpstreamFreshnessStatus.Cached -> UpstreamFreshnessStatus.Cached
        WireUpstreamFreshnessStatus.Unavailable -> UpstreamFreshnessStatus.Unavailable
      },
    lastAttemptAtMs = lastAttemptAt?.let(parseTimestamp),
    lastSuccessAtMs = lastSuccessAt?.let(parseTimestamp),
  )

private fun QuotaSourceStatus.toWire() =
  when (this) {
    QuotaSourceStatus.Ok -> WireQuotaSourceStatus.Ok
    QuotaSourceStatus.Partial -> WireQuotaSourceStatus.Partial
    QuotaSourceStatus.Unavailable -> WireQuotaSourceStatus.Unavailable
    QuotaSourceStatus.Paused -> WireQuotaSourceStatus.Paused
  }

private fun QuotaWindowStatus.toWire() =
  when (this) {
    QuotaWindowStatus.Current -> WireQuotaWindowStatus.Current
    QuotaWindowStatus.PendingSync -> WireQuotaWindowStatus.PendingSync
    QuotaWindowStatus.Unknown -> WireQuotaWindowStatus.Unknown
  }

private fun ResetInventoryStatus.toWire() =
  when (this) {
    ResetInventoryStatus.Cached -> WireResetInventoryStatus.Cached
    ResetInventoryStatus.CachedDerived -> WireResetInventoryStatus.CachedDerived
    ResetInventoryStatus.Missing -> WireResetInventoryStatus.Missing
    ResetInventoryStatus.Unavailable -> WireResetInventoryStatus.Unavailable
  }

private fun ComputerLinkStatus.toWire() =
  when (this) {
    ComputerLinkStatus.Online -> WireComputerLinkStatus.Online
    ComputerLinkStatus.Offline -> WireComputerLinkStatus.Offline
    ComputerLinkStatus.Paused -> WireComputerLinkStatus.Paused
  }

private fun CodexLinkStatus.toWire() =
  when (this) {
    CodexLinkStatus.Ok -> WireCodexLinkStatus.Ok
    CodexLinkStatus.Unavailable -> WireCodexLinkStatus.Unavailable
    CodexLinkStatus.Stale -> WireCodexLinkStatus.Stale
    CodexLinkStatus.FormatChanged -> WireCodexLinkStatus.FormatChanged
  }
