package com.codex.quota.android.protocol

import com.codex.quota.android.domain.SyncedTask
import com.codex.quota.android.domain.SafeActivity
import com.codex.quota.android.domain.TaskState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ChatGptState {
  Running,
  NotRunning,
  HookUnavailable,
}

data class TaskSnapshot(
  val sequence: Long,
  val generatedAtMs: Long,
  val chatGptState: ChatGptState,
  val chatGptFocused: Boolean,
  val tasks: List<SyncedTask>,
)

object TaskWireContract {
  private val json =
    Json {
      ignoreUnknownKeys = false
      explicitNulls = true
    }

  fun decode(payload: String): TaskSnapshot {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "task snapshot is too large"
    }
    val wire = json.decodeFromString<TaskWireSnapshot>(payload)
    require(wire.protocolVersion == PROTOCOL_VERSION) { "unsupported task protocol" }
    require(wire.sequence >= 0 && wire.generatedAtMs >= 0) { "invalid task snapshot metadata" }
    val conversationIds = HashSet<String>()
    for (task in wire.tasks) {
      require(
        task.conversationId.isNotBlank() &&
          task.conversationId.length <= 128 &&
          task.conversationId.none(Char::isISOControl) &&
          conversationIds.add(task.conversationId),
      ) {
        "invalid task identity"
      }
      val titleLength = task.title.codePoints().count()
      require(titleLength in 1..16 && task.title.none(Char::isISOControl)) {
        "invalid task title"
      }
      require(task.updatedAtMs >= 0) { "invalid task timestamp" }
    }
    return TaskSnapshot(
      sequence = wire.sequence,
      generatedAtMs = wire.generatedAtMs,
      chatGptState = wire.chatGptState.toDomain(),
      chatGptFocused = wire.chatGptFocused,
      tasks =
        wire.tasks.map { task ->
          SyncedTask(
            conversationId = task.conversationId,
            title = task.title,
            state = task.state.toDomain(),
            activity = task.activity?.toDomain(),
            updatedAtMs = task.updatedAtMs,
          )
        },
    )
  }

  private const val PROTOCOL_VERSION = 1
  private const val MAX_PAYLOAD_BYTES = 64 * 1024
}

@Serializable
private data class TaskWireSnapshot(
  val protocolVersion: Int,
  val sequence: Long,
  val generatedAtMs: Long,
  val chatGptState: WireChatGptState,
  val chatGptFocused: Boolean,
  val tasks: List<TaskWireItem>,
)

@Serializable
private data class TaskWireItem(
  val conversationId: String,
  val title: String,
  val state: WireTaskState,
  val activity: WireActivity? = null,
  val updatedAtMs: Long,
)

@Serializable
private enum class WireChatGptState {
  @SerialName("running") Running,
  @SerialName("not_running") NotRunning,
  @SerialName("hook_unavailable") HookUnavailable,
}

@Serializable
private enum class WireTaskState {
  @SerialName("running") Running,
  @SerialName("needs_authorization") NeedsAuthorization,
  @SerialName("waiting_for_review") WaitingForReview,
}

@Serializable
private enum class WireActivity {
  @SerialName("executing_command") ExecutingCommand,
  @SerialName("modifying_files") ModifyingFiles,
  @SerialName("using_browser") UsingBrowser,
}

private fun WireChatGptState.toDomain() =
  when (this) {
    WireChatGptState.Running -> ChatGptState.Running
    WireChatGptState.NotRunning -> ChatGptState.NotRunning
    WireChatGptState.HookUnavailable -> ChatGptState.HookUnavailable
  }

private fun WireTaskState.toDomain() =
  when (this) {
    WireTaskState.Running -> TaskState.Running
    WireTaskState.NeedsAuthorization -> TaskState.NeedsAuthorization
    WireTaskState.WaitingForReview -> TaskState.WaitingForReview
  }

private fun WireActivity.toDomain() =
  when (this) {
    WireActivity.ExecutingCommand -> SafeActivity.ExecutingCommand
    WireActivity.ModifyingFiles -> SafeActivity.ModifyingFiles
    WireActivity.UsingBrowser -> SafeActivity.UsingBrowser
  }
