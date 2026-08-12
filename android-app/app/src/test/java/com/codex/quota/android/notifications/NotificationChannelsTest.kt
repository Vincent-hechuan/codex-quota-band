package com.codex.quota.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelsTest {
  @Test
  fun bothTaskChannelsRequestHeadsUpAndVibrationWithoutSound() {
    val authorization =
      NotificationChannels.specs.single {
        it.id == NotificationChannels.NEEDS_AUTHORIZATION_CHANNEL_ID
      }
    val waiting =
      NotificationChannels.specs.single {
        it.id == NotificationChannels.WAITING_FOR_REVIEW_CHANNEL_ID
      }

    assertEquals(ChannelImportance.High, authorization.importance)
    assertTrue(authorization.vibrate)
    assertEquals(ChannelImportance.High, waiting.importance)
    assertTrue(waiting.vibrate)
  }

  @Test
  fun headsUpUpgradeUsesFreshChannelIdentifiers() {
    assertEquals(
      listOf("needs-authorization-v4", "waiting-for-review-v4"),
      NotificationChannels.specs.map(NotificationChannelSpec::id),
    )
  }
}
