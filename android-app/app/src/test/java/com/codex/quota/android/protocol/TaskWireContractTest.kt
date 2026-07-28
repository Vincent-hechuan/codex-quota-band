package com.codex.quota.android.protocol

import com.codex.quota.android.domain.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskWireContractTest {
  @Test
  fun decodesThePrivacyMinimizedWindowsTaskSnapshot() {
    val snapshot =
      TaskWireContract.decode(
        """
        {
          "protocolVersion": 1,
          "sequence": 42,
          "generatedAtMs": 1721800000000,
          "chatGptState": "running",
          "chatGptFocused": false,
          "tasks": [
            {
              "conversationId": "session-42",
              "title": "整理今天的素材",
              "state": "needs_authorization",
              "updatedAtMs": 1721800000000
            }
          ]
        }
        """.trimIndent(),
      )

    assertEquals(42, snapshot.sequence)
    assertEquals(ChatGptState.Running, snapshot.chatGptState)
    assertEquals(TaskState.NeedsAuthorization, snapshot.tasks.single().state)
    assertEquals("整理今天的素材", snapshot.tasks.single().title)
  }

  @Test
  fun rejectsPrivateOrUnknownFieldsInsteadOfIgnoringThem() {
    val payload =
      """
      {
        "protocolVersion": 1,
        "sequence": 1,
        "generatedAtMs": 1721800000000,
        "chatGptState": "running",
        "chatGptFocused": false,
        "tasks": [
          {
            "conversationId": "session-42",
            "title": "任务",
            "state": "running",
            "updatedAtMs": 1721800000000,
            "prompt": "不应进入协议"
          }
        ]
      }
      """.trimIndent()

    assertThrows(Exception::class.java) { TaskWireContract.decode(payload) }
  }

  @Test
  fun rejectsTaskTitlesOutsideThePublicSixteenCharacterLimit() {
    val payload =
      """
      {
        "protocolVersion": 1,
        "sequence": 1,
        "generatedAtMs": 1721800000000,
        "chatGptState": "running",
        "chatGptFocused": false,
        "tasks": [
          {
            "conversationId": "session-42",
            "title": "这是一个超过十六个字符而且不应被接受的任务标题",
            "state": "running",
            "updatedAtMs": 1721800000000
          }
        ]
      }
      """.trimIndent()

    assertThrows(IllegalArgumentException::class.java) { TaskWireContract.decode(payload) }
  }
}
