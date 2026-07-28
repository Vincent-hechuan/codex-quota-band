package com.codex.quota.android.domain

enum class SafeActivity {
  ExecutingCommand,
  ModifyingFiles,
  UsingBrowser,
}

data class SyncedTask(
  val conversationId: String,
  val title: String,
  val state: TaskState,
  val activity: SafeActivity? = null,
  val updatedAtMs: Long,
)

data class TaskBoard(
  val phoneTasks: List<SyncedTask>,
  val bandTasks: List<SyncedTask>,
) {
  companion object {
    fun from(tasks: List<SyncedTask>): TaskBoard {
      val ordered = tasks.sortedWith(taskOrder)
      val phoneTasks =
        ordered.filter { it.state != TaskState.WaitingForReview } +
          ordered.filter { it.state == TaskState.WaitingForReview }.take(10)
      return TaskBoard(
        phoneTasks = phoneTasks,
        bandTasks = ordered.take(3),
      )
    }

    private val taskOrder =
      compareBy<SyncedTask> { task ->
          when (task.state) {
            TaskState.NeedsAuthorization -> 0
            TaskState.Running -> 1
            TaskState.WaitingForReview -> 2
          }
        }
        .thenByDescending(SyncedTask::updatedAtMs)
        .thenBy(SyncedTask::conversationId)
  }
}
