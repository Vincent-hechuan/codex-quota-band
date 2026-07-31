package com.codex.quota.android.runtime

internal data class WearableRegistrationPlan(
  val registerMessages: Boolean,
  val subscribeConnection: Boolean,
)

internal class WearableRegistrationState {
  private var nodeId: String? = null
  private var messagesRegistered = false
  private var messageRegistrationPending = false
  private var connectionSubscribed = false
  private var connectionSubscriptionPending = false

  fun planFor(nodeId: String): WearableRegistrationPlan {
    if (this.nodeId != nodeId) {
      this.nodeId = nodeId
      resetRegistrationFlags()
    }
    val plan =
      WearableRegistrationPlan(
        registerMessages = !messagesRegistered && !messageRegistrationPending,
        subscribeConnection = !connectionSubscribed && !connectionSubscriptionPending,
      )
    if (plan.registerMessages) messageRegistrationPending = true
    if (plan.subscribeConnection) connectionSubscriptionPending = true
    return plan
  }

  fun markMessageRegistered(nodeId: String) {
    if (this.nodeId != nodeId) return
    messageRegistrationPending = false
    messagesRegistered = true
  }

  fun markConnectionSubscribed(nodeId: String) {
    if (this.nodeId != nodeId) return
    connectionSubscriptionPending = false
    connectionSubscribed = true
  }

  fun markDisconnected(nodeId: String) {
    if (this.nodeId == nodeId) resetRegistrationFlags()
  }

  fun isReady(nodeId: String): Boolean =
    this.nodeId == nodeId && messagesRegistered && connectionSubscribed

  fun clear() {
    nodeId = null
    resetRegistrationFlags()
  }

  private fun resetRegistrationFlags() {
    messagesRegistered = false
    messageRegistrationPending = false
    connectionSubscribed = false
    connectionSubscriptionPending = false
  }
}
