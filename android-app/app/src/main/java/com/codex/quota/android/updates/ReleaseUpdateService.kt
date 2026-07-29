package com.codex.quota.android.updates

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SemanticVersion(
  val major: Int,
  val minor: Int,
  val patch: Int,
) : Comparable<SemanticVersion> {
  override fun compareTo(other: SemanticVersion): Int =
    compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

  override fun toString(): String = "$major.$minor.$patch"

  companion object {
    private val StableVersionPattern = Regex("""^v?(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$""")

    fun parse(value: String): SemanticVersion? {
      val match = StableVersionPattern.matchEntire(value.trim()) ?: return null
      return SemanticVersion(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
      )
    }
  }
}

data class AppRelease(
  val version: SemanticVersion,
  val notes: String,
  val releaseUrl: String,
)

sealed interface UpdateCheckResult {
  data class Available(val release: AppRelease) : UpdateCheckResult

  data object UpToDate : UpdateCheckResult

  data object Skipped : UpdateCheckResult

  data object Failed : UpdateCheckResult
}

fun interface ReleaseTransport {
  fun fetchLatestRelease(): String
}

interface UpdateCheckSchedule {
  fun lastAutomaticAttemptMs(): Long

  fun recordAutomaticAttempt(nowMs: Long)
}

class ReleaseUpdateService(
  currentVersion: String,
  private val transport: ReleaseTransport,
  private val schedule: UpdateCheckSchedule,
) {
  private val installedVersion = SemanticVersion.parse(currentVersion)

  fun check(manual: Boolean, nowMs: Long): UpdateCheckResult {
    if (!manual && !automaticCheckDue(schedule.lastAutomaticAttemptMs(), nowMs)) {
      return UpdateCheckResult.Skipped
    }
    if (!manual) schedule.recordAutomaticAttempt(nowMs)
    val current = installedVersion ?: return UpdateCheckResult.Failed
    return runCatching {
        val release = parseRelease(transport.fetchLatestRelease()) ?: return@runCatching UpdateCheckResult.Failed
        if (release.version > current) UpdateCheckResult.Available(release) else UpdateCheckResult.UpToDate
      }
      .getOrDefault(UpdateCheckResult.Failed)
  }

  private fun parseRelease(payload: String): AppRelease? {
    val release = Json.parseToJsonElement(payload).jsonObject
    if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return null
    if (release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
    val version = SemanticVersion.parse(release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null) ?: return null
    val releaseUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
    if (!isTrustedReleaseUrl(releaseUrl)) return null
    val notes =
      release["body"]?.jsonPrimitive?.contentOrNull
        ?.replace("\r", "")
        ?.trim()
        ?.take(MAX_NOTES_LENGTH)
        .orEmpty()
    return AppRelease(version = version, notes = notes, releaseUrl = releaseUrl)
  }
}

internal fun automaticCheckDue(lastAttemptMs: Long, nowMs: Long): Boolean =
  lastAttemptMs <= 0L || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= AUTOMATIC_CHECK_INTERVAL_MS

private fun isTrustedReleaseUrl(value: String): Boolean =
  runCatching {
      val uri = URI(value)
      uri.scheme == "https" &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.startsWith("/Vincent-hechuan/codex-quota-band/releases/")
    }
    .getOrDefault(false)

private const val AUTOMATIC_CHECK_INTERVAL_MS = 24 * 60 * 60_000L
private const val MAX_NOTES_LENGTH = 280
