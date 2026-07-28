package com.codex.quota.android.permissions

internal fun shouldRequestNotificationPermission(
  sdkInt: Int,
  permissionGranted: Boolean,
  requestAlreadyAttempted: Boolean,
): Boolean = sdkInt >= 33 && !permissionGranted && !requestAlreadyAttempted
