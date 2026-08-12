package com.codex.quota.android.pairing

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingScannerReleaseTest {
  @get:Rule val compose = createAndroidComposeRule<PairingActivity>()

  @Before
  fun grantCameraPermission() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
      instrumentation.targetContext.packageName,
      Manifest.permission.CAMERA,
    )
  }

  @Test
  fun releaseScannerOpensAfterCameraPermissionWithoutCrashing() {
    compose.onNodeWithText("扫描二维码").performClick()
    compose.onNodeWithText("对准 Windows 上的配对二维码").assertIsDisplayed()
    compose.onNodeWithText("扫码功能暂不可用", substring = true).assertDoesNotExist()
  }
}
