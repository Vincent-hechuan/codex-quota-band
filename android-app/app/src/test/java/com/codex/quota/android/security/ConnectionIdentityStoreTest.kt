package com.codex.quota.android.security

import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionIdentityStoreTest {
  @Test
  fun acceptsOnlyPrivateWssSyncEndpoints() {
    ConnectionIdentityStore.requireValidSyncEndpoint(URI("wss://192.168.1.42:17322/sync"))

    for (endpoint in listOf("ws://192.168.1.42:17322/sync", "wss://8.8.8.8:17322/sync", "wss://192.168.1.42:17322/pair")) {
      assertThrows(IllegalArgumentException::class.java) {
        ConnectionIdentityStore.requireValidSyncEndpoint(URI(endpoint))
      }
    }
  }
}
