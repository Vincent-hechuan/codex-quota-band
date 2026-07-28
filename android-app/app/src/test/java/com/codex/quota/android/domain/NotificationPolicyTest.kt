package com.codex.quota.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPolicyTest {
  @Test
  fun defaultPolicyKeepsPhoneUrgencySeparateFromSystemControlledBandBehavior() {
    val policy = NotificationPolicy.default()
    val background =
      AlertContext(
        chatGptFocused = false,
        androidForeground = false,
        reconnect = false,
      )

    assertEquals(
      AlertDelivery(
        phone = true,
        band = true,
        phoneUrgency = PhoneUrgency.Silent,
        bandBehavior = BandAlertBehavior.SystemControlled,
      ),
      policy.plan(TaskState.WaitingForReview, background),
    )
    assertEquals(
      AlertDelivery(
        phone = true,
        band = true,
        phoneUrgency = PhoneUrgency.Vibrate,
        bandBehavior = BandAlertBehavior.SystemControlled,
      ),
      policy.plan(TaskState.NeedsAuthorization, background),
    )
  }
}
