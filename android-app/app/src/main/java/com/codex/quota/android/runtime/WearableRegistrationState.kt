package com.codex.quota.android.runtime

internal data class WearableRegistrationPlan(
  val registerMessages: Boolean,
  val subscribeConnection: Boolean,
  val registrationGeneration: Long,
)

internal class WearableRegistrationState {
  private var nodeId: String? = null
  private var registrationGeneration = 0L
  private var messagesRegistered = false
  private var messageRegistrationPending = false
  private var connectionSubscribed = false
  private var connectionSubscriptionPending = false
  private var communicationConfirmed = false

  fun planFor(nodeId: String, restart: Boolean = false): WearableRegistrationPlan {
    if (this.nodeId != nodeId || restart) {
      this.nodeId = nodeId
      registrationGeneration += 1
      resetRegistrationFlags()
    }
    val plan =
      WearableRegistrationPlan(
        registerMessages = !messagesRegistered && !messageRegistrationPending,
        subscribeConnection = !connectionSubscribed && !connectionSubscriptionPending,
        registrationGeneration = registrationGeneration,
      )
    if (plan.registerMessages) messageRegistrationPending = true
    if (plan.subscribeConnection) connectionSubscriptionPending = true
    return plan
  }

  fun markMessageRegistered(nodeId: String, generation: Long) {
    if (!isCurrent(nodeId, generation)) return
    messageRegistrationPending = false
    messagesRegistered = true
  }

  fun markConnectionSubscribed(nodeId: String, generation: Long) {
    if (!isCurrent(nodeId, generation)) return
    connectionSubscriptionPending = false
    connectionSubscribed = true
  }

  fun markDisconnected(nodeId: String, generation: Long? = null) {
    if (this.nodeId != nodeId || (generation != null && generation != registrationGeneration)) return
    registrationGeneration += 1
    resetRegistrationFlags()
  }

  fun markCommunicationConfirmed(nodeId: String) {
    if (this.nodeId == nodeId) communicationConfirmed = true
  }

  fun hasConfirmedCommunication(nodeId: String): Boolean =
    this.nodeId == nodeId && communicationConfirmed

  fun isReady(nodeId: String): Boolean =
    this.nodeId == nodeId && messagesRegistered && connectionSubscribed

  fun clear() {
    nodeId = null
    registrationGeneration += 1
    resetRegistrationFlags()
  }

  fun isCurrent(nodeId: String, generation: Long): Boolean =
    this.nodeId == nodeId && registrationGeneration == generation

  private fun resetRegistrationFlags() {
    messagesRegistered = false
    messageRegistrationPending = false
    connectionSubscribed = false
    connectionSubscriptionPending = false
    communicationConfirmed = false
  }
}
