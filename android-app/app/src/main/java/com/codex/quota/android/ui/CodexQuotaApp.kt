package com.codex.quota.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.quota.android.domain.SafeActivity
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskBoard
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.updates.AppRelease
import kotlinx.coroutines.delay

private enum class AppTab(val label: String, val icon: ImageVector) {
  Home("首页", Icons.Outlined.Home),
  Tasks("任务", Icons.Outlined.Checklist),
  Settings("设置", Icons.Outlined.Settings),
}

@Composable
fun CodexQuotaApp(
  state: AppUiState,
  modifier: Modifier = Modifier,
  appVersion: String = "0.6.3",
  availableUpdate: AppRelease? = null,
  notificationSettings: NotificationSettings = NotificationSettings.Default,
  onNotificationSettingsChange: (NotificationSettings) -> Unit = {},
  onOpenNotificationSettings: () -> Unit = {},
  onConnectComputer: () -> Unit = {},
  onCheckBandConnection: () -> Unit = {},
  onExportDiagnostics: () -> Unit = {},
  onCheckForUpdates: () -> Unit = {},
  onDismissUpdate: () -> Unit = {},
  onOpenUpdate: (AppRelease) -> Unit = {},
  onRemoveTask: (String) -> Unit = {},
  onRefreshSync: () -> Boolean = { false },
) {
  var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
  val nowMs = rememberAppNowMs()
  val tabs = AppTab.entries
  val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
  val offline = state.syncState == SyncState.Offline

  CodexQuotaTheme {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .background(
              if (darkTheme) {
                Brush.linearGradient(
                  if (offline) {
                    listOf(Color(0xFF13191C), CodexTokens.Color.BackgroundOfflineDark, Color(0xFF121719))
                  } else {
                    listOf(CodexTokens.Color.BackgroundDark, CodexTokens.Color.BackgroundMiddleDark, Color(0xFF131A1E))
                  },
                )
              } else if (offline) {
                Brush.linearGradient(listOf(Color(0xFFF0F3F3), Color(0xFFE3E8E8), Color(0xFFEAEBEA)))
              } else {
                Brush.linearGradient(listOf(Color(0xFFF4F8FA), Color(0xFFEEF2EE), Color(0xFFF7F0E9)))
              },
            ),
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(
                Brush.radialGradient(
                  colors =
                    if (darkTheme) {
                      if (offline) {
                        listOf(Color(0x1F6F7B80), Color.Transparent)
                      } else {
                        listOf(Color(0x304B7280), Color.Transparent)
                      }
                    } else if (offline) {
                      listOf(Color(0x1F92A0A3), Color.Transparent)
                    } else {
                      listOf(Color(0x55D8EBF8), Color.Transparent)
                    },
                  center = Offset.Zero,
                  radius = 860f,
                ),
              ),
        )

        val contentModifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        when (tabs[selectedTabIndex]) {
          AppTab.Home ->
            HomeScreen(
              state = state,
              hideTaskTitles = notificationSettings.hideTaskTitles,
              onShowTasks = { selectedTabIndex = AppTab.Tasks.ordinal },
              onRefreshSync = onRefreshSync,
              onCheckBandConnection = onCheckBandConnection,
              nowMs = nowMs,
              modifier = contentModifier,
            )
          AppTab.Tasks ->
            TasksScreen(
              state = state,
              hideTaskTitles = notificationSettings.hideTaskTitles,
              onRemoveTask = onRemoveTask,
              nowMs = nowMs,
              modifier = contentModifier,
            )
          AppTab.Settings ->
            SettingsScreen(
              state = state,
              settings = notificationSettings,
              onSettingsChange = onNotificationSettingsChange,
              onOpenNotificationSettings = onOpenNotificationSettings,
              onConnectComputer = onConnectComputer,
              onExportDiagnostics = onExportDiagnostics,
              appVersion = appVersion,
              onCheckForUpdates = onCheckForUpdates,
              nowMs = nowMs,
              modifier = contentModifier,
            )
        }

        CodexBottomNavigation(
          tabs = tabs,
          selectedIndex = selectedTabIndex,
          onSelected = { selectedTabIndex = it },
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(horizontal = 15.dp, vertical = 13.dp),
        )
        availableUpdate?.let { release ->
          UpdateAvailableDialog(
            currentVersion = appVersion,
            release = release,
            onDismiss = onDismissUpdate,
            onOpenRelease = { onOpenUpdate(release) },
          )
        }
      }
    }
  }
}

@Composable
private fun rememberAppNowMs(): Long {
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(millisecondsUntilNextMinute(System.currentTimeMillis()))
      nowMs = System.currentTimeMillis()
    }
  }
  return nowMs
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
  state: AppUiState,
  hideTaskTitles: Boolean,
  onShowTasks: () -> Unit,
  onRefreshSync: () -> Boolean,
  onCheckBandConnection: () -> Unit,
  nowMs: Long,
  modifier: Modifier,
) {
  val phoneTasks = remember(state.tasks) { TaskBoard.from(state.tasks).phoneTasks }
  val authorizationCount = phoneTasks.count { it.state == TaskState.NeedsAuthorization }
  var refreshStartedAtMs by remember { mutableLongStateOf(0L) }
  val isRefreshing = refreshStartedAtMs != 0L

  LaunchedEffect(refreshStartedAtMs) {
    if (refreshStartedAtMs != 0L) {
      delay(8_000)
      refreshStartedAtMs = 0L
    }
  }
  LaunchedEffect(state.lastTransportDataAtMs, refreshStartedAtMs) {
    if (refreshStartedAtMs != 0L && (state.lastTransportDataAtMs ?: 0L) >= refreshStartedAtMs) {
      refreshStartedAtMs = 0L
    }
  }

  PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = {
      if (onRefreshSync()) refreshStartedAtMs = System.currentTimeMillis()
    },
    modifier = modifier.statusBarsPadding(),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(top = 14.dp, bottom = 90.dp),
      verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
      item { HomeHeader(state, nowMs) }
      if (authorizationCount > 0) {
        item {
          AuthorizationAlert(
            count = authorizationCount,
            task = phoneTasks.first { it.state == TaskState.NeedsAuthorization },
            nowMs = nowMs,
            hideTaskTitles = hideTaskTitles,
            onShowTasks = onShowTasks,
          )
        }
      }
      if (state.syncState == SyncState.Offline) item { OfflineCallout() }
      item { QuotaHeroCard(state) }
      item { ConnectionStatusRow(state, onCheckBandConnection) }
      item { TaskSummarySection(tasks = phoneTasks.take(2), nowMs = nowMs, onShowTasks = onShowTasks, state = state, hideTaskTitles = hideTaskTitles) }
      item {
        ResetCreditsSection(
          state.resetAvailableCount,
          state.resetCredits,
        )
      }
    }
  }
}

@Composable
private fun HomeHeader(state: AppUiState, nowMs: Long) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text("Codex额度", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp)
      Text("额度与当前任务", modifier = Modifier.padding(top = 3.dp), fontSize = CodexTokens.Type.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SyncStatusPill(state, nowMs)
  }
}

@Composable
private fun AuthorizationAlert(count: Int, task: SyncedTask, nowMs: Long, hideTaskTitles: Boolean, onShowTasks: () -> Unit) {
  CodexGlassCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    shape = RoundedCornerShape(CodexTokens.Radius.Card),
    shadowElevation = 2.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
      Text("$count 个任务需要你在电脑端处理", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
      Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).background(taskBoardDotColor(task.state), CircleShape))
        Text(
          "${displayTaskTitle(task, hideTaskTitles)} · ${phoneTaskMetadata(task, nowMs)}",
          modifier = Modifier.padding(start = 8.dp),
          fontSize = CodexTokens.Type.Supporting,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        "查看任务详情",
        modifier = Modifier.padding(top = 7.dp).clickable(onClick = onShowTasks),
        fontSize = CodexTokens.Type.Supporting,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun OfflineCallout() {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    shape = RoundedCornerShape(CodexTokens.Radius.Card),
    color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF253137) else Color(0xFFE4EAEB),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, cachedColor().copy(alpha = .28f)),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp).width(3.dp).height(42.dp).background(cachedColor()),
      )
      Column(modifier = Modifier.padding(start = 10.dp, top = 12.dp, end = 13.dp, bottom = 12.dp)) {
        Text("电脑离线", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
        Text("显示上一次同步的数据；恢复连接前不会发送任务提醒。", modifier = Modifier.padding(top = 4.dp), fontSize = CodexTokens.Type.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun QuotaHeroCard(state: AppUiState) {
  CodexGlassCard(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(CodexTokens.Radius.Hero),
  ) {
    Column(modifier = Modifier.padding(start = 19.dp, top = 20.dp, end = 19.dp, bottom = 17.dp)) {
      Text("5小时额度", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
      Box(modifier = Modifier.fillMaxWidth().height(182.dp), contentAlignment = Alignment.Center) {
        QuotaRing(
          quota = state.fiveHourQuota,
          size = 176.dp,
          syncState = state.syncState,
        )
      }
      Text(
        fiveHourResetLabel(state.fiveHourQuota, state.fiveHourQuotaAvailability),
        fontSize = CodexTokens.Type.Supporting,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(modifier = Modifier.padding(top = 14.dp), color = dividerColor())
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("周额度", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
        Text(
          state.weeklyQuota?.let { "${it.remainingPercent}%" } ?: "--",
          fontSize = CodexTokens.Type.SectionTitle,
          fontWeight = FontWeight.Bold,
          color = quotaColor(quotaLevelForDisplay(state.weeklyQuota, state.syncState)),
        )
      }
      QuotaProgressBar(
        quota = state.weeklyQuota,
        syncState = state.syncState,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      )
      Text(
        state.weeklyQuota?.let { weeklyResetDateLabel(it.resetsAtMs) } ?: "重置时间待同步",
        modifier = Modifier.padding(top = 9.dp),
        fontSize = CodexTokens.Type.Supporting,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun QuotaProgressBar(
  quota: WeeklyQuota?,
  syncState: SyncState,
  modifier: Modifier = Modifier,
) {
  val progress = (quota?.remainingPercent ?: 0).coerceIn(0, 100) / 100f
  val progressColor = quotaColor(quotaLevelForDisplay(quota, syncState))
  val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .14f)
  Canvas(modifier = modifier.height(8.dp)) {
    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    drawRoundRect(color = trackColor, cornerRadius = radius)
    if (quota != null && progress > 0f) {
      drawRoundRect(
        color = progressColor,
        size = Size(size.width * progress, size.height),
        cornerRadius = radius,
      )
    }
  }
}

@Composable
private fun ConnectionStatusRow(state: AppUiState, onCheckBandConnection: () -> Unit) {
  CodexGlassCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(76.dp),
    shape = RoundedCornerShape(CodexTokens.Radius.Navigation),
    color = glassSurface(alpha = .35f),
    shadowElevation = 2.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DeviceStatusItem(
        icon = Icons.Outlined.DesktopWindows,
        label = "电脑",
        status = when (state.connections.computer) {
          DeviceLinkState.Connected -> "已连接"
          DeviceLinkState.Disconnected -> "离线"
          DeviceLinkState.Unavailable -> "不可用"
        },
        color = deviceStateColor(state.connections.computer),
        modifier = Modifier.weight(1f),
      )
      Box(modifier = Modifier.width(1.dp).height(38.dp).background(dividerColor()))
      DeviceStatusItem(
        icon = Icons.Outlined.Watch,
        label = "手环",
        status = when (state.connections.band) {
          DeviceLinkState.Connected -> "已连接"
          DeviceLinkState.Disconnected -> "未连接"
          DeviceLinkState.Unavailable -> "不可用"
        },
        color = deviceStateColor(state.connections.band),
        modifier = Modifier.weight(1f).clickable(onClick = onCheckBandConnection),
      )
    }
  }
}

@Composable
private fun DeviceStatusItem(icon: ImageVector, label: String, status: String, color: Color, modifier: Modifier) {
  Column(
    modifier = modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(CodexTokens.Type.Icon))
      Text(label, fontSize = CodexTokens.Type.Body, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    Text(
      status,
      modifier = Modifier.padding(top = 4.dp),
      fontSize = CodexTokens.Type.Supporting,
      lineHeight = 15.sp,
      color = color,
      maxLines = 1,
    )
  }
}

@Composable
private fun TaskSummarySection(state: AppUiState, tasks: List<SyncedTask>, nowMs: Long, onShowTasks: () -> Unit, hideTaskTitles: Boolean) {
  Column(modifier = Modifier.padding(horizontal = 2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("当前任务", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
      Text("查看全部", modifier = Modifier.padding(4.dp).clickable(onClick = onShowTasks), fontSize = CodexTokens.Type.Supporting, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(7.dp))
    CodexGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(CodexTokens.Radius.Card), shadowElevation = 1.dp) {
      if (tasks.isEmpty()) {
        Text(emptyTaskLabel(state), modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        Column(modifier = Modifier.padding(horizontal = 13.dp)) {
          tasks.forEachIndexed { index, task ->
            if (index > 0) HorizontalDivider(color = dividerColor())
            CompactTaskRow(task, nowMs, hideTaskTitles)
          }
        }
      }
    }
  }
}

@Composable
private fun CompactTaskRow(task: SyncedTask, nowMs: Long, hideTaskTitles: Boolean) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(modifier = Modifier.size(7.dp).background(taskBoardDotColor(task.state), CircleShape))
      Text(
        displayTaskTitle(task, hideTaskTitles),
        modifier = Modifier.padding(start = 8.dp),
        fontSize = CodexTokens.Type.Body,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      phoneTaskMetadata(task, nowMs),
      modifier = Modifier.padding(start = 15.dp, top = 3.dp),
      fontSize = CodexTokens.Type.Supporting,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ResetCreditsSection(
  availableCount: Int?,
  credits: List<ResetCredit>,
) {
  val nearest = credits.minByOrNull(ResetCredit::expiresAtMs)
  CodexGlassCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    shape = RoundedCornerShape(CodexTokens.Radius.Card),
    shadowElevation = 1.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("可用重置", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
        Text(
          nearest?.let { resetDateLabel(it.expiresAtMs) }
            ?: if (availableCount == 0) "当前没有可用重置" else "暂无重置数据",
          modifier = Modifier.padding(top = 3.dp),
          fontSize = CodexTokens.Type.Supporting,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        availableCount?.let { "$it 次" } ?: "--",
        modifier = Modifier.padding(start = 12.dp),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = semanticToneColor(resetCountTone(availableCount)),
      )
    }
  }
}

@Composable
private fun TasksScreen(
  state: AppUiState,
  hideTaskTitles: Boolean,
  onRemoveTask: (String) -> Unit,
  nowMs: Long,
  modifier: Modifier,
) {
  var pendingRemoval by remember { mutableStateOf<SyncedTask?>(null) }
  val orderedTasks = remember(state.tasks) { TaskBoard.from(state.tasks).phoneTasks }
  val groups = listOf(TaskState.NeedsAuthorization to "需要授权", TaskState.Running to "处理中", TaskState.WaitingForReview to "等待查看")

  LazyColumn(
    modifier = modifier.statusBarsPadding(),
    contentPadding = PaddingValues(top = 14.dp, bottom = 90.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item { TaskPageHeader(state, nowMs) }
    item { TaskNotice() }
    groups.forEach { (taskState, label) ->
      val tasks = orderedTasks.filter { it.state == taskState }
      if (tasks.isNotEmpty()) {
        item { TaskGroupHeader(label, tasks.size) }
        item {
          CodexGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), shape = RoundedCornerShape(CodexTokens.Radius.Card), shadowElevation = 1.dp) {
            Column(modifier = Modifier.padding(horizontal = 13.dp)) {
              tasks.forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider(color = dividerColor())
                TaskBoardRow(task, nowMs, hideTaskTitles) { pendingRemoval = task }
              }
            }
          }
        }
      }
    }
    if (orderedTasks.isEmpty()) item { EmptyTaskState(state) }
  }
  pendingRemoval?.let { task ->
    AlertDialog(
      onDismissRequest = { pendingRemoval = null },
      title = { Text(if (task.state == TaskState.WaitingForReview) "删除记录？" else "隐藏任务？") },
      text = { Text("${if (hideTaskTitles) "此任务" else "“${task.title}”"}只会从本机任务面板和手环摘要中隐藏；不会删除 ChatGPT 对话。") },
      confirmButton = {
        TextButton(onClick = { onRemoveTask(task.conversationId); pendingRemoval = null }) {
          Text(if (task.state == TaskState.WaitingForReview) "删除" else "隐藏", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
    )
  }
}

@Composable
private fun TaskPageHeader(state: AppUiState, nowMs: Long) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text("任务", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp)
      Text("仅显示本机任务摘要", modifier = Modifier.padding(top = 3.dp), fontSize = CodexTokens.Type.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SyncStatusPill(state, nowMs)
  }
}

@Composable
private fun TaskNotice() {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    shape = RoundedCornerShape(18.dp),
    color = glassSurface(alpha = .42f),
  ) {
    Text(
      "隐藏或删除只影响手机和手环看板，不会删除 ChatGPT 对话；任务有新活动时会自动重新出现。",
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      fontSize = CodexTokens.Type.Caption,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      lineHeight = 15.sp,
    )
  }
}

@Composable
private fun TaskGroupHeader(label: String, count: Int) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
    Text(
      count.toString(),
      fontSize = CodexTokens.Type.Supporting,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TaskBoardRow(task: SyncedTask, nowMs: Long, hideTaskTitles: Boolean, onRequestRemove: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(modifier = Modifier.size(7.dp).background(taskBoardDotColor(task.state), CircleShape))
      Text(
        displayTaskTitle(task, hideTaskTitles),
        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp),
        fontSize = CodexTokens.Type.Body,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        if (task.state == TaskState.WaitingForReview) "删除" else "隐藏",
        modifier = Modifier.padding(start = 4.dp).clickable(onClick = onRequestRemove),
        fontSize = CodexTokens.Type.Supporting,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      phoneTaskMetadata(task, nowMs),
      modifier = Modifier.fillMaxWidth().padding(start = 15.dp, top = 4.dp),
      fontSize = CodexTokens.Type.Caption,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SettingsScreen(
  state: AppUiState,
  settings: NotificationSettings,
  onSettingsChange: (NotificationSettings) -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onConnectComputer: () -> Unit,
  onExportDiagnostics: () -> Unit,
  appVersion: String,
  onCheckForUpdates: () -> Unit,
  nowMs: Long,
  modifier: Modifier,
) {
  LazyColumn(
    modifier = modifier.statusBarsPadding(),
    contentPadding = PaddingValues(top = 14.dp, bottom = 90.dp),
    verticalArrangement = Arrangement.spacedBy(15.dp),
  ) {
    item { SettingsPageHeader(state, nowMs) }
    item {
      SettingsGroup("连接与设备") {
        SettingsActionRow("连接电脑", "扫描二维码或输入配对码", "›", onConnectComputer)
      }
    }
    item {
      SettingsGroup("提醒") {
        Column(modifier = Modifier.padding(vertical = 11.dp)) {
          Text("通知时机", fontSize = CodexTokens.Type.Body, fontWeight = FontWeight.SemiBold)
          Text(reminderTimingDescription(settings.timing), modifier = Modifier.padding(top = 3.dp, bottom = 8.dp), fontSize = CodexTokens.Type.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
          ReminderTimingControl(settings.timing) { onSettingsChange(settings.copy(timing = it)) }
        }
        SettingsDivider()
        SettingsSwitchRow("手机通知", "在手机上接收任务提醒", settings.phoneNotifications) { onSettingsChange(settings.copy(phoneNotifications = it)) }
        SettingsDivider()
        SettingsSwitchRow("手环通知", "在手环上接收任务提醒", settings.bandNotifications) { onSettingsChange(settings.copy(bandNotifications = it)) }
        SettingsDivider()
        SettingsActionRow("系统通知设置", "调整通知权限、悬浮通知和振动", "›", onOpenNotificationSettings)
      }
    }
    item {
      SettingsGroup("显示与数据") {
        SettingsSwitchRow("隐藏看板任务标题", "仅影响本机看板，不影响同步和提醒", settings.hideTaskTitles) {
          onSettingsChange(settings.copy(hideTaskTitles = it))
        }
        SettingsDivider()
        SettingsActionRow("导出本地诊断", "仅含版本、连接状态与同步时间", "›", onExportDiagnostics)
      }
    }
    item {
      SettingsGroup("关于") {
        SettingsActionRow("检查更新", "当前版本 $appVersion", "›", onCheckForUpdates)
      }
    }
  }
}

@Composable
private fun UpdateAvailableDialog(
  currentVersion: String,
  release: AppRelease,
  onDismiss: () -> Unit,
  onOpenRelease: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("发现新版本 ${release.version}") },
    text = {
      Column {
        Text(
          "当前版本 $currentVersion",
          fontSize = CodexTokens.Type.Supporting,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          release.notes.ifBlank { "前往 GitHub Releases 查看本次更新说明。" },
          modifier = Modifier.padding(top = 10.dp),
          fontSize = CodexTokens.Type.Body,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onOpenRelease) {
        Text("前往下载")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("稍后")
      }
    },
  )
}

@Composable
private fun SettingsPageHeader(state: AppUiState, nowMs: Long) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text("设置", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp)
      Text("连接、提醒与本地数据", modifier = Modifier.padding(top = 3.dp), fontSize = CodexTokens.Type.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SyncStatusPill(state, nowMs)
  }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier = Modifier.padding(horizontal = 2.dp)) {
    Text(title, modifier = Modifier.padding(start = 4.dp, bottom = 7.dp), fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
    CodexGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(CodexTokens.Radius.Card), shadowElevation = 1.dp) {
      Column(modifier = Modifier.padding(horizontal = 13.dp), content = content)
    }
  }
}

@Composable
private fun SettingsDivider() = HorizontalDivider(color = dividerColor())

@Composable
private fun SettingsActionRow(label: String, supporting: String, trailing: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, fontSize = CodexTokens.Type.Body, fontWeight = FontWeight.SemiBold)
      Text(supporting, modifier = Modifier.padding(top = 3.dp), fontSize = CodexTokens.Type.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Text(trailing, modifier = Modifier.padding(start = 8.dp), fontSize = if (trailing == "›") 19.sp else CodexTokens.Type.Caption, color = if (trailing == "›") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun SettingsSwitchRow(label: String, supporting: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, fontSize = CodexTokens.Type.Body, fontWeight = FontWeight.SemiBold)
      Text(supporting, modifier = Modifier.padding(top = 3.dp), fontSize = CodexTokens.Type.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    CompactSwitch(checked, onCheckedChange)
  }
}

@Composable
private fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Surface(
    onClick = { onCheckedChange(!checked) },
    modifier = Modifier.size(width = 36.dp, height = 21.dp),
    shape = CircleShape,
    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f),
    shadowElevation = 0.dp,
  ) {
    Box(modifier = Modifier.fillMaxSize().padding(2.dp)) {
      Box(
        modifier =
          Modifier
            .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
            .size(17.dp)
            .background(Color.White, CircleShape),
      )
    }
  }
}

@Composable
private fun ReminderTimingControl(selected: ReminderTiming, onSelected: (ReminderTiming) -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().height(32.dp),
    shape = CircleShape,
    color = Color.Transparent,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .58f)),
  ) {
    Row {
      ReminderTiming.entries.forEachIndexed { index, timing ->
        val active = timing == selected
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
              .clickable { onSelected(timing) },
          contentAlignment = Alignment.Center,
        ) {
          Text(reminderTimingLabel(timing), fontSize = CodexTokens.Type.Supporting, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        if (index < ReminderTiming.entries.lastIndex) Box(modifier = Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)))
      }
    }
  }
}

@Composable
private fun QuotaRing(quota: WeeklyQuota?, size: Dp, syncState: SyncState) {
  val progress by animateFloatAsState(
    targetValue = (quota?.remainingPercent ?: 0) / 100f,
    animationSpec = tween(CodexTokens.Type.MotionMs),
    label = "quota-progress",
  )
  val ringColor = quotaColor(quotaLevelForDisplay(quota, syncState))
  val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .14f)
  Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val strokeWidth = 8.dp.toPx()
      val inset = strokeWidth / 2
      val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
      drawArc(trackColor, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokeWidth))
      if (quota != null) drawArc(ringColor, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(8.dp)
          .clip(CircleShape)
          .background(glassSurface(alpha = .72f))
          .border(1.dp, if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.GlassBorderDark else CodexTokens.Color.GlassBorderLight, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier =
          Modifier.offset(
            x = if (quota != null && size >= 100.dp) 4.dp else 0.dp,
            y = if (quota != null && size >= 100.dp) (-2).dp else 0.dp,
          ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        if (quota == null) {
          Text("--", fontSize = if (size >= 100.dp) 52.sp else 12.sp, fontWeight = FontWeight.SemiBold, color = ringColor)
        } else {
          Row(verticalAlignment = Alignment.Bottom) {
            Text(
              quota.remainingPercent.toString(),
              fontSize = if (size >= 100.dp) 56.sp else 12.sp,
              lineHeight = if (size >= 100.dp) 56.sp else 12.sp,
              fontWeight = FontWeight.SemiBold,
              letterSpacing = if (size >= 100.dp) (-0.7).sp else 0.sp,
              color = ringColor,
            )
            Text(
              "%",
              modifier = Modifier.padding(start = 1.dp, bottom = if (size >= 100.dp) 6.dp else 0.dp),
              fontSize = if (size >= 100.dp) 29.sp else 10.sp,
              lineHeight = if (size >= 100.dp) 30.sp else 10.sp,
              fontWeight = FontWeight.SemiBold,
              color = ringColor,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CodexBottomNavigation(tabs: List<AppTab>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth().height(59.dp),
    shape = RoundedCornerShape(CodexTokens.Radius.Navigation),
    color = glassSurface(alpha = .66f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.GlassBorderDark else CodexTokens.Color.GlassBorderLight),
    shadowElevation = 4.dp,
  ) {
    Row(modifier = Modifier.fillMaxSize().padding(5.dp)) {
      tabs.forEachIndexed { index, tab ->
        val selected = index == selectedIndex
        Surface(
          onClick = { onSelected(index) },
          modifier = Modifier.weight(1f).fillMaxSize(),
          shape = RoundedCornerShape(22.dp),
          color = if (selected) glassSurface(alpha = .72f) else Color.Transparent,
          shadowElevation = if (selected) 1.dp else 0.dp,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              tab.icon,
              contentDescription = tab.label,
              modifier = Modifier.size(24.dp),
              tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CodexGlassCard(
  modifier: Modifier,
  shape: RoundedCornerShape,
  color: Color = glassSurface(),
  borderColor: Color? = null,
  shadowElevation: Dp = 1.dp,
  content: @Composable () -> Unit,
) {
  Box(
    modifier =
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .background(color)
        .border(
          1.dp,
          borderColor
            ?: if (androidx.compose.foundation.isSystemInDarkTheme()) {
              CodexTokens.Color.GlassBorderDark
            } else {
              CodexTokens.Color.GlassBorderLight
            },
          shape,
        ),
    content = { content() },
  )
}

@Composable
private fun StatusPill(label: String, color: Color) {
  Surface(
    modifier = Modifier.widthIn(max = 112.dp),
    shape = CircleShape,
    color = glassSurface(alpha = .54f),
    border = BorderStroke(1.dp, if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.GlassBorderDark else CodexTokens.Color.GlassBorderLight),
    shadowElevation = 1.dp,
  ) {
    Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
      Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
      Text(
        label,
        fontSize = CodexTokens.Type.Supporting,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SyncStatusPill(state: AppUiState, nowMs: Long) =
  StatusPill(
    syncStatusLabel(state.syncState, state.lastSyncAtMs, nowMs, state.usageFreshness),
    syncStateColor(state.syncState),
  )

@Composable
private fun EmptyTaskState(state: AppUiState) {
  CodexGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), shape = RoundedCornerShape(CodexTokens.Radius.Card), shadowElevation = 1.dp) {
    Text(emptyTaskLabel(state), modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun glassSurface(alpha: Float = .56f): Color =
  if (androidx.compose.foundation.isSystemInDarkTheme()) {
    when {
      alpha >= .7f -> CodexTokens.Color.SurfaceStrongDark
      alpha >= .55f -> CodexTokens.Color.SurfaceElevatedDark
      else -> CodexTokens.Color.SurfaceDark
    }
  } else {
    // Compose's translucent layers render with visible seams on this device. These colors are
    // the pre-composited equivalents of the prototype's glass layers over its soft background.
    if (alpha >= .7f) Color(0xFFF9FBFB) else Color(0xFFF6F9F9)
  }

@Composable
private fun dividerColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .15f)

@Composable
private fun taskStateColor(state: TaskState): Color = when (state) {
  TaskState.Running -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.RunningDark else CodexTokens.Color.Running
  TaskState.NeedsAuthorization -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.AuthorizationDark else CodexTokens.Color.Authorization
  TaskState.WaitingForReview -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.WaitingDark else CodexTokens.Color.Waiting
}

@Composable
private fun taskBoardDotColor(state: TaskState): Color =
  when (state) {
    TaskState.NeedsAuthorization -> MaterialTheme.colorScheme.error
    TaskState.Running -> taskStateColor(TaskState.Running)
    TaskState.WaitingForReview -> taskStateColor(TaskState.WaitingForReview)
  }

@Composable
private fun quotaColor(level: QuotaLevel): Color = when (level) {
  QuotaLevel.Healthy -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.WaitingDark else CodexTokens.Color.Waiting
  QuotaLevel.Warning -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.AuthorizationDark else CodexTokens.Color.Authorization
  QuotaLevel.Critical -> if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.ErrorDark else CodexTokens.Color.Error
  QuotaLevel.Unavailable -> cachedColor()
}

@Composable
private fun syncStateColor(state: SyncState): Color = semanticToneColor(syncStatusTone(state))

@Composable
private fun semanticToneColor(tone: SemanticTone): Color =
  when (tone) {
    SemanticTone.Healthy ->
      if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.WaitingDark else CodexTokens.Color.Waiting
    SemanticTone.Warning ->
      if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.AuthorizationDark else CodexTokens.Color.Authorization
    SemanticTone.Danger -> MaterialTheme.colorScheme.error
    SemanticTone.Primary -> MaterialTheme.colorScheme.primary
    SemanticTone.Neutral -> cachedColor()
  }

@Composable
private fun deviceStateColor(state: DeviceLinkState): Color =
  if (state == DeviceLinkState.Connected) {
    if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.WaitingDark else CodexTokens.Color.Waiting
  } else {
    cachedColor()
  }

@Composable
private fun cachedColor(): Color =
  if (androidx.compose.foundation.isSystemInDarkTheme()) CodexTokens.Color.CachedDark else CodexTokens.Color.Cached

private fun reminderTimingLabel(timing: ReminderTiming): String = when (timing) {
  ReminderTiming.Never -> "从不"
  ReminderTiming.Unfocused -> "失焦"
  ReminderTiming.Always -> "始终"
}

internal fun reminderTimingDescription(timing: ReminderTiming): String = when (timing) {
  ReminderTiming.Never -> "不发送任务提醒"
  ReminderTiming.Unfocused -> "仅当 ChatGPT 客户端处于后台时提醒"
  ReminderTiming.Always -> "无论 ChatGPT 客户端是否在前台都提醒"
}

private fun emptyTaskLabel(state: AppUiState): String = when {
  state.connections.computer == DeviceLinkState.Disconnected -> "电脑离线"
  state.chatGptState == ChatGptState.HookUnavailable -> "任务状态不可用"
  state.chatGptState == ChatGptState.NotRunning -> "ChatGPT未运行"
  else -> "暂无任务"
}

private fun displayTaskTitle(task: SyncedTask, hidden: Boolean): String = TaskTitleFormatter.display(task.title, hidden)

@Composable
fun CodexQuotaTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) CodexDarkColors else CodexLightColors, content = content)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun CodexQuotaAppPreview() {
  val now = 1_800_000_000_000L
  CodexQuotaApp(
    state =
      AppUiState(
        SyncState.Synced,
        now - 2 * 60_000,
        WeeklyQuota(34, now + 5 * 24 * 60 * 60_000),
        1,
        listOf(ResetCredit("Full reset", now + 3 * 24 * 60 * 60_000)),
        DeviceConnections(DeviceLinkState.Connected, DeviceLinkState.Connected, DeviceLinkState.Connected),
        ChatGptState.Running,
        listOf(SyncedTask("task-1", "整理 Android 页面信息", TaskState.Running, SafeActivity.ModifyingFiles, now - 60_000)),
        fiveHourQuota = WeeklyQuota(68, now + 3 * 60 * 60_000),
        fiveHourQuotaAvailability = FiveHourQuotaAvailability.Available,
      ),
  )
}
