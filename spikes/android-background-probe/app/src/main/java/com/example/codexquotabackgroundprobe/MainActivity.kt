package com.example.codexquotabackgroundprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.codexquotabackgroundprobe.theme.CodexQuotaBackgroundProbeTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      CodexQuotaBackgroundProbeTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          var snapshot by remember { mutableStateOf(ProbeSnapshot.read(this@MainActivity)) }
          LaunchedEffect(Unit) {
            while (true) {
              snapshot = ProbeSnapshot.read(this@MainActivity)
              delay(1_000)
            }
          }

          Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Codex 后台探针", style = MaterialTheme.typography.headlineSmall)
            Text("一次性验证：无前台服务、无常驻通知")
            Text("连接：${if (snapshot.connected) "已连接" else "未连接"}")
            Text("总事件：${snapshot.total}")
            Text("处理中：${snapshot.running}")
            Text("需要授权：${snapshot.needsAuthorization}")
            Text("等待查看：${snapshot.waitingReview}")
            Text("重连次数：${snapshot.reconnects}")
            Text(
              "通知：尝试 ${snapshot.notificationAttempts} / 已提交 ${snapshot.notificationPosted} / 失败 ${snapshot.notificationFailures}",
            )
            Text(
              "静默：前台 ${snapshot.notificationSuppressedForeground} / 缺权限 ${snapshot.notificationSkippedPermission}",
            )
            Text("前台页面数：${snapshot.foregroundActivities}")
            Text("通知状态：${snapshot.lastNotificationStatus}")
            Text("最后事件：${snapshot.lastEvent}")
            Text("最后时间：${snapshot.lastAt}")

            if (
              Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                  PackageManager.PERMISSION_GRANTED
            ) {
              Button(
                onClick = {
                  requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                },
              ) {
                Text("允许测试通知")
              }
            } else {
              Text("通知权限：已允许")
            }

            Text("看到“已连接”后退出到桌面并锁屏，等待本轮短时通知测试。")
          }
        }
      }
    }
  }
}
