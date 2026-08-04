package com.codex.quota.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableRegistrationStateTest {
  @Test
  fun connectionLossRequiresBothListenersToBeRegisteredAgain() {
    val state = WearableRegistrationState()

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true, registrationGeneration = 1), state.planFor("band-1"))

    state.markMessageRegistered("band-1", 1)
    state.markConnectionSubscribed("band-1", 1)
    assertEquals(WearableRegistrationPlan(registerMessages = false, subscribeConnection = false, registrationGeneration = 1), state.planFor("band-1"))

    state.markDisconnected("band-1")

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true, registrationGeneration = 2), state.planFor("band-1"))
  }

  @Test
  fun repeatedConnectionCallbackDoesNotDuplicatePendingRegistrations() {
    val state = WearableRegistrationState()

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true, registrationGeneration = 1), state.planFor("band-1"))

    assertEquals(WearableRegistrationPlan(registerMessages = false, subscribeConnection = false, registrationGeneration = 1), state.planFor("band-1"))
  }

  @Test
  fun explicitConnectionCheckRestartsRegistrationsThatNeverCompleted() {
    val state = WearableRegistrationState()

    val stalled = state.planFor("band-1")
    val restarted = state.planFor("band-1", restart = true)

    assertEquals(true, stalled.registerMessages)
    assertEquals(true, stalled.subscribeConnection)
    assertEquals(true, restarted.registerMessages)
    assertEquals(true, restarted.subscribeConnection)
    assertEquals(false, stalled.registrationGeneration == restarted.registrationGeneration)
  }

  @Test
  fun lateCallbacksFromAnEarlierRegistrationCannotReplaceTheLatestState() {
    val state = WearableRegistrationState()

    val stale = state.planFor("band-1")
    val latest = state.planFor("band-1", restart = true)
    state.markMessageRegistered("band-1", stale.registrationGeneration)
    state.markConnectionSubscribed("band-1", stale.registrationGeneration)

    assertEquals(false, state.isReady("band-1"))

    state.markMessageRegistered("band-1", latest.registrationGeneration)
    state.markConnectionSubscribed("band-1", latest.registrationGeneration)

    assertEquals(true, state.isReady("band-1"))
  }

  @Test
  fun realBandCommunicationRemainsAuthoritativeUntilADisconnectEvent() {
    val state = WearableRegistrationState()
    state.planFor("band-1")

    state.markCommunicationConfirmed("band-1")

    assertTrue(state.hasConfirmedCommunication("band-1"))

    state.markDisconnected("band-1")

    assertFalse(state.hasConfirmedCommunication("band-1"))
  }
}
