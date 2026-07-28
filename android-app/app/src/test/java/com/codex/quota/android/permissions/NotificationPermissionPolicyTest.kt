package com.codex.quota.android.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
  @Test
  fun requestsOnceOnAndroidThirteenAndLater() {
    assertTrue(
      shouldRequestNotificationPermission(
        sdkInt = 36,
        permissionGranted = false,
        requestAlreadyAttempted = false,
      ),
    )
    assertFalse(
      shouldRequestNotificationPermission(
        sdkInt = 36,
        permissionGranted = false,
        requestAlreadyAttempted = true,
      ),
    )
  }

  @Test
  fun neverRequestsWhenPermissionIsGrantedOrNotRuntimeControlled() {
    assertFalse(
      shouldRequestNotificationPermission(
        sdkInt = 36,
        permissionGranted = true,
        requestAlreadyAttempted = false,
      ),
    )
    assertFalse(
      shouldRequestNotificationPermission(
        sdkInt = 32,
        permissionGranted = false,
        requestAlreadyAttempted = false,
      ),
    )
  }
}
