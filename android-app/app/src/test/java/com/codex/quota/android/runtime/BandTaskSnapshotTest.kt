package com.codex.quota.android.runtime

import com.codex.quota.android.domain.SafeActivity
import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.TaskState
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.TaskSnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BandTaskSnapshotTest {
  @Test
  fun nullTaskStateRemainsExplicitlyUnavailable() {
    assertEquals(JsonNull, buildBandTaskSnapshot(null))
  }

  @Test
  fun bandPayloadKeepsThreePrioritizedPrivacyMinimizedTasks() {
    val snapshot =
      TaskSnapshot(
        sequence = 7,
        generatedAtMs = 1_784_896_000_000,
        chatGptState = ChatGptState.Running,
        chatGptFocused = false,
        tasks =
          listOf(
            task("waiting-old", "旧任务", TaskState.WaitingForReview, 1),
            task("running", "构建安装包", TaskState.Running, 3, SafeActivity.ExecutingCommand),
            task("waiting-new", "检查页面", TaskState.WaitingForReview, 4),
            task("authorization", "允许写入", TaskState.NeedsAuthorization, 2),
          ),
      )

    val payload = buildBandTaskSnapshot(snapshot).jsonObject
    val tasks = payload.getValue("tasks").jsonArray

    assertEquals("running", payload.getValue("chatGptState").jsonPrimitive.content)
    assertEquals(3, tasks.size)
    assertEquals(
      listOf("允许写入", "构建安装包", "检查页面"),
      tasks.map { it.jsonObject.getValue("title").jsonPrimitive.content },
    )
    assertEquals(
      "executing_command",
      tasks[1].jsonObject.getValue("activity").jsonPrimitive.content,
    )
    assertFalse(tasks[0].jsonObject.containsKey("conversationId"))
    assertFalse(payload.containsKey("sequence"))
    assertFalse(payload.containsKey("chatGptFocused"))
  }

  private fun task(
    id: String,
    title: String,
    state: TaskState,
    updatedAtMs: Long,
    activity: SafeActivity? = null,
  ) =
    SyncedTask(
      conversationId = id,
      title = title,
      state = state,
      activity = activity,
      updatedAtMs = updatedAtMs,
    )
}
