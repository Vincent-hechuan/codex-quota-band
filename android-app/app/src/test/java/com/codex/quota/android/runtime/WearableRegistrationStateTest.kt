package com.codex.quota.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WearableRegistrationStateTest {
  @Test
  fun connectionLossRequiresBothListenersToBeRegisteredAgain() {
    val state = WearableRegistrationState()

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true), state.planFor("band-1"))

    state.markMessageRegistered("band-1")
    state.markConnectionSubscribed("band-1")
    assertEquals(WearableRegistrationPlan(registerMessages = false, subscribeConnection = false), state.planFor("band-1"))

    state.markDisconnected("band-1")

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true), state.planFor("band-1"))
  }

  @Test
  fun repeatedConnectionCallbackDoesNotDuplicatePendingRegistrations() {
    val state = WearableRegistrationState()

    assertEquals(WearableRegistrationPlan(registerMessages = true, subscribeConnection = true), state.planFor("band-1"))

    assertEquals(WearableRegistrationPlan(registerMessages = false, subscribeConnection = false), state.planFor("band-1"))
  }
}
