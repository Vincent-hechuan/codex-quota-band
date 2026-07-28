package com.codex.quota.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.codex.quota.android.diagnostics.DiagnosticReport
import com.codex.quota.android.ui.AppUiState
import com.codex.quota.android.ui.bandConnectionCheckResultLabel
import com.codex.quota.android.permissions.shouldRequestNotificationPermission
import com.codex.quota.android.ui.CodexQuotaApp
import com.codex.quota.android.ui.NotificationSettingsStore

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
  private var pendingDiagnosticJson: String? = null
  private val diagnosticExportLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      val report = pendingDiagnosticJson
      pendingDiagnosticJson = null
      if (uri == null || report == null) return@registerForActivityResult
      val written =
        runCatching {
          val output = contentResolver.openOutputStream(uri) ?: error("cannot open diagnostic output")
          output.bufferedWriter().use { it.write(report) }
        }.isSuccess
      Toast.makeText(this, if (written) "诊断已导出" else "诊断导出失败", Toast.LENGTH_SHORT).show()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val settingsStore = remember { NotificationSettingsStore(applicationContext) }
      val runtimeRepository = remember { (application as CodexQuotaApplication).runtimeRepository }
      val runtimeState by runtimeRepository.state.collectAsState()
      val quotaApplication = application as CodexQuotaApplication
      var notificationSettings by remember { mutableStateOf(settingsStore.load()) }
      CodexQuotaApp(
        state = runtimeState,
        notificationSettings = notificationSettings,
        onNotificationSettingsChange = { updatedSettings ->
          notificationSettings = updatedSettings
          settingsStore.save(updatedSettings)
          quotaApplication.updateNotificationSettings(updatedSettings)
        },
        onOpenNotificationSettings = ::openNotificationSettings,
        onOpenPairingCamera = ::openPairingCamera,
        onCheckBandConnection = {
          quotaApplication.checkBandConnection { granted ->
            runOnUiThread {
              Toast.makeText(this, bandConnectionCheckResultLabel(granted), Toast.LENGTH_LONG).show()
            }
          }
        },
        onExportDiagnostics = { requestDiagnosticExport(runtimeState) },
        onRemoveTask = { conversationId -> runtimeRepository.removeTaskFromBoard(conversationId) },
        onRefreshSync = quotaApplication::refreshSync,
      )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      requestNotificationPermissionOnce()
    }
    intent.data?.let { (application as CodexQuotaApplication).handlePairingLink(it) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let { (application as CodexQuotaApplication).handlePairingLink(it) }
  }

  override fun onStart() {
    super.onStart()
    (application as CodexQuotaApplication).setAndroidForeground(true)
  }

  override fun onStop() {
    (application as CodexQuotaApplication).setAndroidForeground(false)
    super.onStop()
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun requestNotificationPermissionOnce() {
    val preferences =
      getSharedPreferences(PERMISSION_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val permissionGranted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val requestAlreadyAttempted =
      preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    if (
      !shouldRequestNotificationPermission(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = permissionGranted,
        requestAlreadyAttempted = requestAlreadyAttempted,
      )
    ) {
      return
    }
    preferences.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun openNotificationSettings() {
    val notificationSettings =
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
      }
    val applicationDetails =
      Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:$packageName".toUri(),
      )
    runCatching { startActivity(notificationSettings) }
      .onFailure { startActivity(applicationDetails) }
  }

  private fun openPairingCamera() {
    val cameraIntent =
      Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallbackIntent =
      Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val intent =
      when {
        cameraIntent.resolveActivity(packageManager) != null -> cameraIntent
        fallbackIntent.resolveActivity(packageManager) != null -> fallbackIntent
        else -> null
      }
    if (intent != null) {
      startActivity(intent)
    }
  }

  private fun requestDiagnosticExport(state: AppUiState) {
    val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    pendingDiagnosticJson =
      DiagnosticReport.render(
        state = state,
        generatedAtMs = System.currentTimeMillis(),
        appVersion = versionName,
      )
    diagnosticExportLauncher.launch("codexquota-diagnostics-${System.currentTimeMillis()}.json")
  }

  private companion object {
    const val PERMISSION_PREFERENCES_NAME = "permission-prompts"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification-permission-requested"
  }
}
