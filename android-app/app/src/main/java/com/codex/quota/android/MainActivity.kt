package com.codex.quota.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.codex.quota.android.permissions.shouldRequestNotificationPermission
import com.codex.quota.android.protocol.PairingDiscoveryWireContract
import com.codex.quota.android.ui.AppUiState
import com.codex.quota.android.ui.CodexQuotaApp
import com.codex.quota.android.ui.FiveHourQuotaAvailability
import com.codex.quota.android.ui.NotificationSettingsStore
import com.codex.quota.android.ui.WeeklyQuota
import com.codex.quota.android.ui.bandConnectionCheckResultLabel
import com.codex.quota.android.updates.AppRelease
import com.codex.quota.android.updates.GitHubReleaseTransport
import com.codex.quota.android.updates.ReleaseUpdateService
import com.codex.quota.android.updates.SharedPreferencesUpdateCheckSchedule
import com.codex.quota.android.updates.UpdateCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var availableUpdate by mutableStateOf<AppRelease?>(null)
  private var updateCheckRunning = false
  private val appVersion by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
  private val releaseUpdateService by lazy {
    ReleaseUpdateService(
      currentVersion = appVersion,
      transport = GitHubReleaseTransport(appVersion),
      schedule = SharedPreferencesUpdateCheckSchedule(applicationContext),
    )
  }
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
  private val pairingLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != RESULT_OK) return@registerForActivityResult
      val rawLink = result.data?.getStringExtra(com.codex.quota.android.pairing.PairingActivity.EXTRA_PAIRING_LINK)
      if (rawLink != null) {
        (application as CodexQuotaApplication).handlePairingLink(rawLink.toUri())
        return@registerForActivityResult
      }
      val discoveryPayload =
        result.data?.getStringExtra(com.codex.quota.android.pairing.PairingActivity.EXTRA_DISCOVERY_PAYLOAD)
          ?: return@registerForActivityResult
      val pairingCode =
        result.data?.getStringExtra(com.codex.quota.android.pairing.PairingActivity.EXTRA_PAIRING_CODE)
          ?: return@registerForActivityResult
      runCatching { PairingDiscoveryWireContract.decode(discoveryPayload).withPairingCode(pairingCode) }
        .onSuccess { (application as CodexQuotaApplication).handlePairingOffer(it) }
        .onFailure { Toast.makeText(this, "配对信息无效，请重新获取", Toast.LENGTH_LONG).show() }
    }
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
      val displayState =
        if (BuildConfig.DEMO_FIVE_HOUR_QUOTA) {
          runtimeState.copy(
            fiveHourQuota =
              WeeklyQuota(
                remainingPercent = 68,
                resetsAtMs = System.currentTimeMillis() + THREE_HOURS_MS,
              ),
            fiveHourQuotaAvailability = FiveHourQuotaAvailability.Available,
          )
        } else {
          runtimeState
        }
      val quotaApplication = application as CodexQuotaApplication
      var notificationSettings by remember { mutableStateOf(settingsStore.load()) }
      CodexQuotaApp(
        state = displayState,
        appVersion = appVersion,
        availableUpdate = availableUpdate,
        notificationSettings = notificationSettings,
        onNotificationSettingsChange = { updatedSettings ->
          notificationSettings = updatedSettings
          settingsStore.save(updatedSettings)
          quotaApplication.updateNotificationSettings(updatedSettings)
        },
        onOpenNotificationSettings = ::openNotificationSettings,
        onConnectComputer = ::openPairing,
        onCheckBandConnection = {
          Toast.makeText(this, "正在检查手环连接", Toast.LENGTH_SHORT).show()
          quotaApplication.checkBandConnection { result ->
            runOnUiThread {
              Toast.makeText(this, bandConnectionCheckResultLabel(result), Toast.LENGTH_LONG).show()
            }
          }
        },
        onExportDiagnostics = { requestDiagnosticExport(runtimeState) },
        onCheckForUpdates = { checkForUpdates(manual = true) },
        onDismissUpdate = { availableUpdate = null },
        onOpenUpdate = { release ->
          availableUpdate = null
          openRelease(release)
        },
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
    checkForUpdates(manual = false)
  }

  override fun onDestroy() {
    updateScope.cancel()
    super.onDestroy()
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

  private fun openPairing() {
    pairingLauncher.launch(Intent(this, com.codex.quota.android.pairing.PairingActivity::class.java))
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

  private fun checkForUpdates(manual: Boolean) {
    if (updateCheckRunning) {
      if (manual) Toast.makeText(this, "正在检查更新", Toast.LENGTH_SHORT).show()
      return
    }
    updateCheckRunning = true
    updateScope.launch {
      val result =
        withContext(Dispatchers.IO) {
          releaseUpdateService.check(manual = manual, nowMs = System.currentTimeMillis())
        }
      updateCheckRunning = false
      when (result) {
        is UpdateCheckResult.Available -> availableUpdate = result.release
        UpdateCheckResult.UpToDate -> if (manual) Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
        UpdateCheckResult.Failed -> if (manual) Toast.makeText(this@MainActivity, "检查失败，请稍后重试", Toast.LENGTH_SHORT).show()
        UpdateCheckResult.Skipped -> Unit
      }
    }
  }

  private fun openRelease(release: AppRelease) {
    val intent = Intent(Intent.ACTION_VIEW, release.releaseUrl.toUri())
    runCatching { startActivity(intent) }
      .onFailure {
        Toast.makeText(this, "无法打开下载页面", Toast.LENGTH_SHORT).show()
      }
  }

  private companion object {
    const val THREE_HOURS_MS = 3 * 60 * 60_000L
    const val PERMISSION_PREFERENCES_NAME = "permission-prompts"
    const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification-permission-requested"
  }
}
