package com.codex.quota.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {
  @Test
  fun boardKeepsAllActiveTenLatestWaitingAndThreePrioritizedBandTasks() {
    val tasks =
      buildList {
        repeat(12) { index ->
          add(
            SyncedTask(
              conversationId = "waiting-$index",
              title = "等待任务 $index",
              state = TaskState.WaitingForReview,
              updatedAtMs = index.toLong(),
            ),
          )
        }
        add(
          SyncedTask(
            conversationId = "running",
            title = "处理中任务",
            state = TaskState.Running,
            updatedAtMs = 100,
          ),
        )
        add(
          SyncedTask(
            conversationId = "authorization",
            title = "授权任务",
            state = TaskState.NeedsAuthorization,
            updatedAtMs = 99,
          ),
        )
      }

    val board = TaskBoard.from(tasks)

    assertEquals(12, board.phoneTasks.size)
    assertTrue(board.phoneTasks.any { it.conversationId == "running" })
    assertTrue(board.phoneTasks.any { it.conversationId == "authorization" })
    assertFalse(board.phoneTasks.any { it.conversationId == "waiting-0" })
    assertFalse(board.phoneTasks.any { it.conversationId == "waiting-1" })
    assertEquals(
      listOf("authorization", "running", "waiting-11"),
      board.bandTasks.map(SyncedTask::conversationId),
    )
  }
}
