package com.codex.quota.android

import android.app.Application
import android.net.Uri
import com.codex.quota.android.notifications.NotificationChannels
import com.codex.quota.android.notifications.TaskAlertCoordinator
import com.codex.quota.android.notifications.TaskNotificationDispatcher
import com.codex.quota.android.protocol.PairingDeepLinkContract
import com.codex.quota.android.runtime.PairingClient
import com.codex.quota.android.runtime.BandConnectionCheckResult
import com.codex.quota.android.runtime.RuntimeStateRepository
import com.codex.quota.android.runtime.SharedPreferencesTaskVisibilityStore
import com.codex.quota.android.runtime.SyncWebSocketClient
import com.codex.quota.android.runtime.XiaomiWearableBridge
import com.codex.quota.android.security.ConnectionIdentityStore
import com.codex.quota.android.security.PairingCredentialStore
import com.codex.quota.android.ui.NotificationSettings
import com.codex.quota.android.ui.NotificationSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodexQuotaApplication : Application() {
  val runtimeRepository by lazy { RuntimeStateRepository(taskVisibility = SharedPreferencesTaskVisibilityStore(this)) }
  private lateinit var wearableBridge: XiaomiWearableBridge

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var credentialStore: PairingCredentialStore
  private lateinit var connectionStore: ConnectionIdentityStore
  private lateinit var syncClient: SyncWebSocketClient
  private lateinit var taskAlerts: TaskAlertCoordinator

  override fun onCreate() {
    super.onCreate()
    NotificationChannels.create(this)
    credentialStore = PairingCredentialStore(this)
    connectionStore = ConnectionIdentityStore(this)
    wearableBridge = XiaomiWearableBridge(this, runtimeRepository)
    val phoneDispatcher = TaskNotificationDispatcher(this)
    taskAlerts =
      TaskAlertCoordinator(
        phoneDispatcher = { phoneDispatcher.notify(it) },
        bandDispatcher = { wearableBridge.sendTaskAlert(it) },
      )
    taskAlerts.updateSettings(NotificationSettingsStore(this).load())
    syncClient = SyncWebSocketClient(applicationScope, runtimeRepository, taskAlerts)
    wearableBridge.start()
    startSavedConnection()
  }

  fun checkBandConnection(onResult: (BandConnectionCheckResult) -> Unit = {}) {
    wearableBridge.checkConnection(onResult)
  }

  fun updateNotificationSettings(settings: NotificationSettings) {
    taskAlerts.updateSettings(settings)
  }

  fun setAndroidForeground(foreground: Boolean) {
    taskAlerts.setAndroidForeground(foreground)
  }

  fun handlePairingLink(uri: Uri) {
    applicationScope.launch {
      runCatching {
          val offer = PairingDeepLinkContract.decode(uri)
          val result = PairingClient().pair(offer, connectionStore.clientInstanceId())
          credentialStore.save(result.credentials)
          connectionStore.saveSyncEndpoint(result.syncEndpoint)
          syncClient.start(
            result.syncEndpoint,
            result.credentials,
            connectionStore.clientInstanceId(),
          )
        }
        .onFailure { runtimeRepository.markTransportDisconnected() }
    }
  }

  fun refreshSync(): Boolean = syncClient.refresh()

  private fun startSavedConnection() {
    val credentials = credentialStore.load() ?: return
    val endpoint = connectionStore.loadSyncEndpoint() ?: return
    syncClient.start(endpoint, credentials, connectionStore.clientInstanceId())
  }
}
