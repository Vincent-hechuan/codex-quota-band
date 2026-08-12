package com.codex.quota.android.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.codex.quota.android.BuildConfig
import com.codex.quota.android.domain.SafeActivity
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskBoard
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.notifications.TaskNotificationContent
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.CodexLinkStatus
import com.codex.quota.android.protocol.ComputerLinkStatus
import com.codex.quota.android.protocol.QuotaSnapshot
import com.codex.quota.android.protocol.QuotaSourceStatus
import com.codex.quota.android.protocol.QuotaWindow
import com.codex.quota.android.protocol.QuotaWindowStatus
import com.codex.quota.android.protocol.ResetInventoryStatus
import com.codex.quota.android.protocol.TaskSnapshot
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.node.Node
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

enum class BandConnectionCheckResult {
  Connected,
  NotConnected,
  PermissionDenied,
}

private class PendingBandConnectionCheck(
  private val callback: (BandConnectionCheckResult) -> Unit,
) {
  private val completed = AtomicBoolean(false)
  private val timeoutArmed = AtomicBoolean(false)

  fun complete(result: BandConnectionCheckResult) {
    if (completed.compareAndSet(false, true)) callback(result)
  }

  fun armTimeout(): Boolean = timeoutArmed.compareAndSet(false, true)
}

/**
 * Direct Android -> Xiaomi Vela bridge. AstroBox is deliberately not involved here.
 * The bridge only sends the already-filtered quota snapshot and never accepts control data.
 */
class XiaomiWearableBridge(
  context: Context,
  private val repository: RuntimeStateRepository,
) {
  private val appContext = context.applicationContext
  private val nodeApi = Wearable.getNodeApi(appContext)
  private val authApi = Wearable.getAuthApi(appContext)
  private val messageApi = Wearable.getMessageApi(appContext)
  private val notifyApi = Wearable.getNotifyApi(appContext)
  private val registrationState = WearableRegistrationState()
  private val refreshState = WearableRefreshState()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var activeNode: Node? = null

  fun start() {
    refresh()
  }

  fun checkConnection(onResult: (BandConnectionCheckResult) -> Unit) {
    refresh(
      requestPermissionIfNeeded = true,
      restartRegistration = true,
      check = PendingBandConnectionCheck(onResult),
    )
  }

  private fun requestPermissionForNode(
    node: Node,
    refreshGeneration: Long,
    onResult: (Boolean) -> Unit,
  ) {
    authApi
      .requestPermission(node.id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
      .addOnSuccessListener {
        if (refreshState.isCurrent(refreshGeneration) && activeNode?.id == node.id) onResult(true)
      }
      .addOnFailureListener {
        if (refreshState.isCurrent(refreshGeneration) && activeNode?.id == node.id) onResult(false)
      }
  }

  fun refresh() {
    refresh(requestPermissionIfNeeded = false, restartRegistration = false, check = null)
  }

  private fun refresh(
    requestPermissionIfNeeded: Boolean,
    restartRegistration: Boolean,
    check: PendingBandConnectionCheck?,
  ) {
    val refreshGeneration = refreshState.begin()
    nodeApi
      .connectedNodes
      .addOnSuccessListener { nodes ->
        if (!refreshState.isCurrent(refreshGeneration)) return@addOnSuccessListener
        val node = nodes.firstOrNull()
        updateActiveNode(node)
        if (node == null) {
          publishDisconnected(check)
          return@addOnSuccessListener
        }
        nodeApi.isWearAppInstalled(node.id).addOnSuccessListener { installed ->
          if (!refreshState.isCurrent(refreshGeneration)) return@addOnSuccessListener
          if (!installed) {
            publishDisconnected(check)
            return@addOnSuccessListener
          }
          authApi
            .checkPermissions(node.id, REQUIRED_PERMISSIONS)
            .addOnSuccessListener { granted ->
              if (!refreshState.isCurrent(refreshGeneration)) return@addOnSuccessListener
              if (granted.size < REQUIRED_PERMISSIONS.size || granted.any { !it }) {
                if (requestPermissionIfNeeded) {
                  requestPermissionForNode(node, refreshGeneration) { permissionGranted ->
                    if (permissionGranted) {
                      startConnectionProbe(node, refreshGeneration, restartRegistration, check)
                    } else {
                      repository.setBandConnected(false)
                      check?.complete(BandConnectionCheckResult.PermissionDenied)
                    }
                  }
                } else {
                  publishDisconnected(check)
                }
                return@addOnSuccessListener
              }
              startConnectionProbe(node, refreshGeneration, restartRegistration, check)
            }
            .addOnFailureListener {
              if (refreshState.isCurrent(refreshGeneration)) publishDisconnected(check)
            }
        }.addOnFailureListener {
          if (refreshState.isCurrent(refreshGeneration)) publishDisconnected(check)
        }
      }
      .addOnFailureListener {
        if (refreshState.isCurrent(refreshGeneration)) {
          activeNode = null
          publishDisconnected(check)
        }
      }
  }

  private fun startConnectionProbe(
    node: Node,
    refreshGeneration: Long,
    restartRegistration: Boolean,
    check: PendingBandConnectionCheck?,
  ) {
    if (check?.armTimeout() == true) {
      mainHandler.postDelayed(
        {
          if (refreshState.isCurrent(refreshGeneration) && activeNode?.id == node.id) {
            if (registrationState.isReady(node.id)) {
              check.complete(BandConnectionCheckResult.Connected)
            } else {
              repository.setBandConnected(false)
              check.complete(BandConnectionCheckResult.NotConnected)
            }
          }
        },
        CONNECTION_CHECK_TIMEOUT_MS,
      )
    }
    probeConnection(node, refreshGeneration, attempt = 0, restartRegistration, check)
  }

  fun stop() {
    activeNode?.let(::unregisterListeners)
    repository.setBandConnected(false)
    activeNode = null
    registrationState.clear()
    refreshState.invalidate()
  }

  fun sendTaskAlert(task: SyncedTask): Boolean {
    val node = activeNode
    val content = TaskNotificationContent.from(task)
    if (node == null || content == null) return false
    val requested = runCatching {
        notifyApi.sendNotify(node.id, content.title, content.body)
        true
      }
      .getOrDefault(false)
    return requested
  }

  private fun registerListeners(
    node: Node,
    refreshGeneration: Long,
    attempt: Int,
    restartRegistration: Boolean,
    check: PendingBandConnectionCheck?,
  ) {
    if (restartRegistration) unregisterListeners(node)
    val plan = registrationState.planFor(node.id, restart = restartRegistration)
    if (plan.registerMessages) {
      messageApi
        .addListener(node.id, messageListener)
        .addOnSuccessListener {
          if (!isCurrentRegistration(node.id, refreshGeneration, plan.registrationGeneration)) return@addOnSuccessListener
          registrationState.markMessageRegistered(node.id, plan.registrationGeneration)
          publishRegistrationState(node.id, refreshGeneration, plan.registrationGeneration, check)
        }
        .addOnFailureListener {
          handleRegistrationFailure(node, refreshGeneration, plan.registrationGeneration, attempt, check)
        }
    }
    if (plan.subscribeConnection) {
      nodeApi
        .subscribe(node.id, DataItem.ITEM_CONNECTION, connectionListener)
        .addOnSuccessListener {
          if (!isCurrentRegistration(node.id, refreshGeneration, plan.registrationGeneration)) return@addOnSuccessListener
          registrationState.markConnectionSubscribed(node.id, plan.registrationGeneration)
          publishRegistrationState(node.id, refreshGeneration, plan.registrationGeneration, check)
        }
        .addOnFailureListener {
          handleRegistrationFailure(node, refreshGeneration, plan.registrationGeneration, attempt, check)
        }
    }
    publishRegistrationState(node.id, refreshGeneration, plan.registrationGeneration, check)
  }

  private fun handleRegistrationFailure(
    node: Node,
    refreshGeneration: Long,
    registrationGeneration: Long,
    attempt: Int,
    check: PendingBandConnectionCheck?,
  ) {
    if (!isCurrentRegistration(node.id, refreshGeneration, registrationGeneration)) return
    registrationState.markDisconnected(node.id, registrationGeneration)
    repository.setBandConnected(false)
    scheduleConnectionProbe(node, refreshGeneration, attempt, restartRegistration = false, check)
  }

  private fun isCurrentRegistration(
    nodeId: String,
    refreshGeneration: Long,
    registrationGeneration: Long,
  ): Boolean =
    refreshState.isCurrent(refreshGeneration) &&
      activeNode?.id == nodeId &&
      registrationState.isCurrent(nodeId, registrationGeneration)

  private fun updateActiveNode(node: Node?) {
    val previous = activeNode
    if (previous?.id == node?.id) return
    if (previous != null) unregisterListeners(previous)
    registrationState.clear()
    activeNode = node
  }

  private fun probeConnection(
    node: Node,
    refreshGeneration: Long,
    attempt: Int,
    restartRegistration: Boolean,
    check: PendingBandConnectionCheck?,
  ) {
    if (!refreshState.isCurrent(refreshGeneration) || activeNode?.id != node.id) return
    nodeApi
      .query(node.id, DataItem.ITEM_CONNECTION)
      .addOnSuccessListener { state ->
        if (!refreshState.isCurrent(refreshGeneration) || activeNode?.id != node.id) return@addOnSuccessListener
        if (state.isConnected) {
          registerListeners(node, refreshGeneration, attempt, restartRegistration, check)
        } else if (registrationState.hasConfirmedCommunication(node.id)) {
          repository.setBandConnected(true)
          check?.complete(BandConnectionCheckResult.Connected)
        } else {
          repository.setBandConnected(false)
          scheduleConnectionProbe(node, refreshGeneration, attempt, restartRegistration, check)
        }
      }
      .addOnFailureListener {
        if (!refreshState.isCurrent(refreshGeneration) || activeNode?.id != node.id) return@addOnFailureListener
        if (registrationState.hasConfirmedCommunication(node.id)) {
          repository.setBandConnected(true)
          check?.complete(BandConnectionCheckResult.Connected)
        } else {
          repository.setBandConnected(false)
          scheduleConnectionProbe(node, refreshGeneration, attempt, restartRegistration, check)
        }
      }
  }

  private fun scheduleConnectionProbe(
    node: Node,
    refreshGeneration: Long,
    attempt: Int,
    restartRegistration: Boolean,
    check: PendingBandConnectionCheck?,
  ) {
    val delayMs = refreshState.connectionRetryDelayMs(attempt)
    if (delayMs == null) {
      check?.complete(BandConnectionCheckResult.NotConnected)
      return
    }
    mainHandler.postDelayed(
      {
        if (refreshState.isCurrent(refreshGeneration) && activeNode?.id == node.id) {
          probeConnection(node, refreshGeneration, attempt + 1, restartRegistration, check)
        }
      },
      delayMs,
    )
  }

  private fun unregisterListeners(node: Node) {
    messageApi.removeListener(node.id)
    nodeApi.unsubscribe(node.id, DataItem.ITEM_CONNECTION)
  }

  private fun publishRegistrationState(
    nodeId: String,
    refreshGeneration: Long,
    registrationGeneration: Long,
    check: PendingBandConnectionCheck?,
  ) {
    if (!isCurrentRegistration(nodeId, refreshGeneration, registrationGeneration)) return
    val connected = registrationState.isReady(nodeId)
    repository.setBandConnected(connected)
    if (connected) check?.complete(BandConnectionCheckResult.Connected)
  }

  private fun publishDisconnected(check: PendingBandConnectionCheck?) {
    repository.setBandConnected(false)
    check?.complete(BandConnectionCheckResult.NotConnected)
  }

  private fun handleIncoming(nodeId: String, bytes: ByteArray) {
    val node = activeNode?.takeIf { it.id == nodeId } ?: return
    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
      ?: return
    if (root["type"]?.jsonPrimitive?.content != "quota_request") return
    val nonce = root["nonce"]?.jsonPrimitive?.content ?: return
    if (nonce.isBlank() || nonce.length > 64) return
    registrationState.markCommunicationConfirmed(nodeId)
    repository.setBandConnected(true)
    val snapshot =
      repository.latestQuotaSnapshot()?.let {
        if (BuildConfig.DEMO_FIVE_HOUR_QUOTA) it.withDemoFiveHourQuota() else it
      }
    val taskSnapshot = buildBandTaskSnapshot(repository.latestTaskSnapshot())
    val payload =
      if (snapshot == null) {
        buildJsonObject {
          put("type", JsonPrimitive("quota_error"))
          put("nonce", JsonPrimitive(nonce))
          put("code", JsonPrimitive("quota_unavailable"))
          put("taskSnapshot", taskSnapshot)
        }
      } else {
        buildJsonObject {
          put("type", JsonPrimitive("quota_snapshot"))
          put("nonce", JsonPrimitive(nonce))
          put("snapshot", buildBandQuotaSnapshot(snapshot))
          put("taskSnapshot", taskSnapshot)
        }
      }
    messageApi.sendMessage(node.id, payload.toString().toByteArray(Charsets.UTF_8))
  }

  private val messageListener = OnMessageReceivedListener { nodeId, bytes -> handleIncoming(nodeId, bytes) }

  private val connectionListener = OnDataChangedListener { nodeId, _, result ->
    if (activeNode?.id != nodeId) return@OnDataChangedListener
    if (result.connectedStatus == 1) {
      refresh()
    } else {
      refreshState.invalidate()
      registrationState.markDisconnected(nodeId)
      repository.setBandConnected(false)
    }
  }

  private companion object {
    val REQUIRED_PERMISSIONS = arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)
    const val CONNECTION_CHECK_TIMEOUT_MS = 10_000L
  }
}

/**
 * Wearable-only quota summary. This is deliberately separate from the Windows v1/v2 wire
 * contracts: reset-card identities and titles never cross the phone-to-band boundary.
 */
internal fun buildBandQuotaSnapshot(
  snapshot: QuotaSnapshot,
  nowMs: Long = System.currentTimeMillis(),
): JsonElement =
  buildJsonObject {
    put("protocolVersion", JsonPrimitive(BAND_QUOTA_VERSION))
    // The current RPK uses generatedAt to render cache age. Forward the time when quota was last
    // confirmed, not the time an already-stale envelope happened to reach the band.
    put("generatedAt", JsonPrimitive(formatBandTimestamp(snapshot.bandConfirmationAtMs())))
    put("sourceStatus", JsonPrimitive(snapshot.effectiveBandSourceStatus(nowMs).bandValue()))
    if (snapshot.limitsCollectedAtMs == null) put("limitsCollectedAt", JsonNull)
    else put("limitsCollectedAt", JsonPrimitive(formatBandTimestamp(snapshot.limitsCollectedAtMs)))
    put(
      "windows",
      buildJsonArray {
        snapshot.windows.forEach { window ->
          add(
            buildJsonObject {
              put("id", JsonPrimitive(window.id))
              put("name", JsonPrimitive(window.name))
              put("windowMinutes", JsonPrimitive(window.windowMinutes))
              if (window.remainingPercent == null) put("remainingPercent", JsonNull)
              else put("remainingPercent", JsonPrimitive(window.remainingPercent))
              put("resetsAt", JsonPrimitive(formatBandTimestamp(window.resetsAtMs)))
              put("status", JsonPrimitive(window.status.bandValue()))
            },
          )
        }
      },
    )
    put(
      "resetInventory",
      buildJsonObject {
        put("status", JsonPrimitive(snapshot.resetInventory.status.bandValue()))
        if (snapshot.resetInventory.availableCount == null) put("availableCount", JsonNull)
        else put("availableCount", JsonPrimitive(snapshot.resetInventory.availableCount))
        if (snapshot.resetInventory.cachedAtMs == null) put("cachedAt", JsonNull)
        else put("cachedAt", JsonPrimitive(formatBandTimestamp(snapshot.resetInventory.cachedAtMs)))
        put(
          "items",
          buildJsonArray {
            snapshot.resetInventory.items.forEach { item ->
              add(
                buildJsonObject {
                  put("status", JsonPrimitive("available"))
                  if (item.grantedAtMs == null) put("grantedAt", JsonNull)
                  else put("grantedAt", JsonPrimitive(formatBandTimestamp(item.grantedAtMs)))
                  put("expiresAt", JsonPrimitive(formatBandTimestamp(item.expiresAtMs)))
                },
              )
            }
          },
        )
      },
    )
    put(
      "link",
      buildJsonObject {
        put("computer", JsonPrimitive(snapshot.computerLink.bandValue()))
        put("codex", JsonPrimitive(snapshot.codexLink.bandValue()))
      },
    )
  }

internal fun QuotaSnapshot.withDemoFiveHourQuota(
  nowMs: Long = System.currentTimeMillis(),
): QuotaSnapshot =
  copy(
    windows =
      windows.filterNot { it.name == "five_hour" || it.windowMinutes == FIVE_HOUR_WINDOW_MINUTES } +
        QuotaWindow(
          id = "demo-five-hour",
          name = "five_hour",
          windowMinutes = FIVE_HOUR_WINDOW_MINUTES,
          remainingPercent = DEMO_FIVE_HOUR_PERCENT,
          resetsAtMs = nowMs + DEMO_FIVE_HOUR_RESET_DELAY_MS,
          status = QuotaWindowStatus.Current,
        ),
  )

private fun formatBandTimestamp(value: Long): String =
  java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(value), java.time.ZoneOffset.UTC)
    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private fun QuotaSourceStatus.bandValue() =
  when (this) {
    QuotaSourceStatus.Ok -> "ok"
    QuotaSourceStatus.Partial -> "partial"
    QuotaSourceStatus.Unavailable -> "unavailable"
    QuotaSourceStatus.Paused -> "paused"
  }

private fun QuotaSnapshot.effectiveBandSourceStatus(nowMs: Long): QuotaSourceStatus {
  val usageFreshness = upstreamFreshness?.usage
  return when (usageFreshness?.status) {
    UpstreamFreshnessStatus.Current ->
      if (nowMs - (usageFreshness.lastSuccessAtMs ?: return QuotaSourceStatus.Partial) <= BAND_CURRENT_QUOTA_MAX_AGE_MS) sourceStatus
      else QuotaSourceStatus.Partial
    null -> sourceStatus
    UpstreamFreshnessStatus.Cached -> QuotaSourceStatus.Partial
    UpstreamFreshnessStatus.Unavailable -> QuotaSourceStatus.Unavailable
  }
}

private fun QuotaSnapshot.bandConfirmationAtMs(): Long =
  upstreamFreshness?.usage?.lastSuccessAtMs ?: generatedAtMs

private fun QuotaWindowStatus.bandValue() =
  when (this) {
    QuotaWindowStatus.Current -> "current"
    QuotaWindowStatus.PendingSync -> "pending_sync"
    QuotaWindowStatus.Unknown -> "unknown"
  }

private fun ResetInventoryStatus.bandValue() =
  when (this) {
    ResetInventoryStatus.Cached -> "cached"
    ResetInventoryStatus.CachedDerived -> "cached_derived"
    ResetInventoryStatus.Missing -> "missing"
    ResetInventoryStatus.Unavailable -> "unavailable"
  }

private fun ComputerLinkStatus.bandValue() =
  when (this) {
    ComputerLinkStatus.Online -> "online"
    ComputerLinkStatus.Offline -> "offline"
    ComputerLinkStatus.Paused -> "paused"
  }

private fun CodexLinkStatus.bandValue() =
  when (this) {
    CodexLinkStatus.Ok -> "ok"
    CodexLinkStatus.Unavailable -> "unavailable"
    CodexLinkStatus.Stale -> "stale"
    CodexLinkStatus.FormatChanged -> "format_changed"
  }

private const val BAND_QUOTA_VERSION = 2
private const val BAND_CURRENT_QUOTA_MAX_AGE_MS = 120_000L
private const val FIVE_HOUR_WINDOW_MINUTES = 300
private const val DEMO_FIVE_HOUR_PERCENT = 68
private const val DEMO_FIVE_HOUR_RESET_DELAY_MS = 3 * 60 * 60_000L

internal fun buildBandTaskSnapshot(snapshot: TaskSnapshot?): JsonElement {
  if (snapshot == null) return JsonNull
  val tasks = TaskBoard.from(snapshot.tasks).bandTasks
  return buildJsonObject {
    put("generatedAtMs", JsonPrimitive(snapshot.generatedAtMs))
    put("chatGptState", JsonPrimitive(snapshot.chatGptState.wireValue()))
    put(
      "tasks",
      buildJsonArray {
        tasks.forEach { task ->
          add(
            buildJsonObject {
              put("title", JsonPrimitive(task.title))
              put("state", JsonPrimitive(task.state.wireValue()))
              task.activity?.let { put("activity", JsonPrimitive(it.wireValue())) }
              put("updatedAtMs", JsonPrimitive(task.updatedAtMs))
            },
          )
        }
      },
    )
  }
}

private fun ChatGptState.wireValue() =
  when (this) {
    ChatGptState.Running -> "running"
    ChatGptState.NotRunning -> "not_running"
    ChatGptState.HookUnavailable -> "hook_unavailable"
  }

private fun TaskState.wireValue() =
  when (this) {
    TaskState.Running -> "running"
    TaskState.NeedsAuthorization -> "needs_authorization"
    TaskState.WaitingForReview -> "waiting_for_review"
  }

private fun SafeActivity.wireValue() =
  when (this) {
    SafeActivity.ExecutingCommand -> "executing_command"
    SafeActivity.ModifyingFiles -> "modifying_files"
    SafeActivity.UsingBrowser -> "using_browser"
  }
