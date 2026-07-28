package com.codex.quota.android.domain

enum class TaskState {
  Running,
  NeedsAuthorization,
  WaitingForReview,
}

enum class NotificationMode {
  Never,
  WhenChatGptUnfocused,
  Always,
}

enum class PhoneUrgency {
  Silent,
  Vibrate,
}

enum class BandAlertBehavior {
  SystemControlled,
}

data class AlertContext(
  val chatGptFocused: Boolean,
  val androidForeground: Boolean,
  val reconnect: Boolean,
)

data class AlertDelivery(
  val phone: Boolean,
  val band: Boolean,
  val phoneUrgency: PhoneUrgency?,
  val bandBehavior: BandAlertBehavior?,
) {
  companion object {
    val None =
      AlertDelivery(
        phone = false,
        band = false,
        phoneUrgency = null,
        bandBehavior = null,
      )
  }
}

class NotificationPolicy private constructor(
  private val mode: NotificationMode,
  private val waitingForReview: Boolean,
  private val needsAuthorization: Boolean,
  private val phone: Boolean,
  private val band: Boolean,
) {
  fun plan(state: TaskState, context: AlertContext): AlertDelivery {
    val eventEnabled =
      when (state) {
        TaskState.Running -> false
        TaskState.NeedsAuthorization -> needsAuthorization
        TaskState.WaitingForReview -> waitingForReview
      }
    val timingAllows =
      when (mode) {
        NotificationMode.Never -> false
        NotificationMode.WhenChatGptUnfocused -> !context.chatGptFocused
        NotificationMode.Always -> true
      }
    if (
      !eventEnabled ||
        !timingAllows ||
        context.androidForeground ||
        (context.reconnect && state == TaskState.WaitingForReview) ||
        (!phone && !band)
    ) {
      return AlertDelivery.None
    }

    return AlertDelivery(
      phone = phone,
      band = band,
      phoneUrgency =
        if (phone) {
          when (state) {
            TaskState.NeedsAuthorization -> PhoneUrgency.Vibrate
            TaskState.WaitingForReview -> PhoneUrgency.Silent
            TaskState.Running -> null
          }
        } else {
          null
        },
      bandBehavior = if (band) BandAlertBehavior.SystemControlled else null,
    )
  }

  companion object {
    fun create(
      mode: NotificationMode,
      waitingForReview: Boolean,
      needsAuthorization: Boolean,
      phone: Boolean,
      band: Boolean,
    ) =
      NotificationPolicy(
        mode = mode,
        waitingForReview = waitingForReview,
        needsAuthorization = needsAuthorization,
        phone = phone,
        band = band,
      )

    fun default() =
      create(
        mode = NotificationMode.WhenChatGptUnfocused,
        waitingForReview = true,
        needsAuthorization = true,
        phone = true,
        band = true,
      )
  }
}
