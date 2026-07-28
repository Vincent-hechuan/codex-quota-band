package com.codex.quota.android.runtime

import android.content.Context
import androidx.core.content.edit
import com.codex.quota.android.domain.SyncedTask

/**
 * Keeps a user's local task-board removals. A removal is intentionally tied to one observed
 * state/version: the next real task change makes the task visible again.
 */
interface TaskVisibilityStore {
  fun hide(task: SyncedTask)

  fun isHidden(task: SyncedTask): Boolean
}

class InMemoryTaskVisibilityStore : TaskVisibilityStore {
  private val hidden = mutableMapOf<String, TaskMarker>()

  override fun hide(task: SyncedTask) {
    hidden[task.conversationId] = task.marker()
  }

  override fun isHidden(task: SyncedTask): Boolean =
    when (val marker = hidden[task.conversationId]) {
      task.marker() -> true
      null -> false
      else -> {
        hidden.remove(task.conversationId)
        false
      }
    }
}

class SharedPreferencesTaskVisibilityStore(context: Context) : TaskVisibilityStore {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun hide(task: SyncedTask) {
    preferences.edit { putString(key(task.conversationId), task.marker().encode()) }
  }

  override fun isHidden(task: SyncedTask): Boolean {
    val key = key(task.conversationId)
    val marker = TaskMarker.decode(preferences.getString(key, null))
    return when (marker) {
      task.marker() -> true
      null -> false
      else -> {
        preferences.edit { remove(key) }
        false
      }
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "task-visibility"
    const val KEY_PREFIX = "hidden:"

    fun key(conversationId: String) = KEY_PREFIX + conversationId
  }
}

private data class TaskMarker(
  val state: String,
  val updatedAtMs: Long,
) {
  fun encode() = "$state|$updatedAtMs"

  companion object {
    fun decode(value: String?): TaskMarker? {
      val separator = value?.indexOf('|') ?: return null
      if (separator <= 0) return null
      val state = value.substring(0, separator)
      val updatedAtMs = value.substring(separator + 1).toLongOrNull() ?: return null
      return TaskMarker(state, updatedAtMs)
    }
  }
}

private fun SyncedTask.marker() = TaskMarker(state.name, updatedAtMs)
