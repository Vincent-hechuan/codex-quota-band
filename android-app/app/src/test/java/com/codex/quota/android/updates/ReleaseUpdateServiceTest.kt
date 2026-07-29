package com.codex.quota.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateServiceTest {
  @Test
  fun stableSemanticVersionsCompareWithoutDowngrading() {
    assertTrue(SemanticVersion.parse("v0.6.1")!! > SemanticVersion.parse("0.6.0")!!)
    assertEquals(SemanticVersion(0, 6, 0), SemanticVersion.parse("0.6.0"))
    assertEquals(null, SemanticVersion.parse("0.6.0-beta.1"))
    assertEquals(null, SemanticVersion.parse("0.6"))
    assertEquals(null, SemanticVersion.parse("00.6.0"))
  }

  @Test
  fun availableStableReleaseUsesOnlyTheExpectedRepositoryUrl() {
    val service =
      ReleaseUpdateService(
        currentVersion = "0.6.0",
        transport =
          FakeTransport(
            """
            {
              "tag_name": "v0.6.1",
              "html_url": "https://github.com/Vincent-hechuan/codex-quota-band/releases/tag/v0.6.1",
              "body": "新增 5 小时额度。\n\n修复缓存时间显示。",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent(),
          ),
        schedule = MemorySchedule(),
      )

    val result = service.check(manual = true, nowMs = 100L)

    assertTrue(result is UpdateCheckResult.Available)
    val release = (result as UpdateCheckResult.Available).release
    assertEquals("0.6.1", release.version.toString())
    assertEquals("新增 5 小时额度。\n\n修复缓存时间显示。", release.notes)
  }

  @Test
  fun prereleaseMalformedOrForeignResponsesFailClosed() {
    val bodies =
      listOf(
        """{"tag_name":"v0.6.1-beta.1","html_url":"https://github.com/Vincent-hechuan/codex-quota-band/releases/tag/v0.6.1-beta.1","body":"","draft":false,"prerelease":true}""",
        """{"tag_name":"v0.6.1","html_url":"https://example.com/file.apk","body":"","draft":false,"prerelease":false}""",
        """{"tag_name":"v0.6.1"}""",
      )

    bodies.forEach { body ->
      val result =
        ReleaseUpdateService("0.6.0", FakeTransport(body), MemorySchedule())
          .check(manual = true, nowMs = 100L)
      assertTrue(result is UpdateCheckResult.Failed)
    }
  }

  @Test
  fun automaticChecksRunAtMostOncePerDayAndStaySilentWhenSkipped() {
    val schedule = MemorySchedule(lastAttemptMs = 1_000L)
    val transport =
      FakeTransport(
        """{"tag_name":"v0.6.0","html_url":"https://github.com/Vincent-hechuan/codex-quota-band/releases/tag/v0.6.0","body":"","draft":false,"prerelease":false}""",
      )
    val service = ReleaseUpdateService("0.6.0", transport, schedule)

    assertEquals(UpdateCheckResult.Skipped, service.check(manual = false, nowMs = 1_000L + 23 * 60 * 60_000L))
    assertEquals(0, transport.calls)

    val due = service.check(manual = false, nowMs = 1_000L + 24 * 60 * 60_000L)
    assertEquals(UpdateCheckResult.UpToDate, due)
    assertEquals(1, transport.calls)
    assertEquals(1_000L + 24 * 60 * 60_000L, schedule.lastAttemptMs)

    service.check(manual = true, nowMs = schedule.lastAttemptMs + 1L)
    assertEquals("manual checks are never blocked by the daily schedule", 2, transport.calls)
  }

  @Test
  fun olderReleaseNeverProducesAnUpdate() {
    val service =
      ReleaseUpdateService(
        "0.6.0",
        FakeTransport(
          """{"tag_name":"v0.5.9","html_url":"https://github.com/Vincent-hechuan/codex-quota-band/releases/tag/v0.5.9","body":"","draft":false,"prerelease":false}""",
        ),
        MemorySchedule(),
      )

    assertEquals(UpdateCheckResult.UpToDate, service.check(manual = true, nowMs = 100L))
  }

  private class FakeTransport(private val body: String) : ReleaseTransport {
    var calls = 0

    override fun fetchLatestRelease(): String {
      calls += 1
      return body
    }
  }

  private class MemorySchedule(var lastAttemptMs: Long = 0L) : UpdateCheckSchedule {
    override fun lastAutomaticAttemptMs(): Long = lastAttemptMs

    override fun recordAutomaticAttempt(nowMs: Long) {
      lastAttemptMs = nowMs
    }
  }
}
