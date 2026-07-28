package com.codex.quota.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncClientHelloWireContractTest {
  @Test
  fun advertisesQuotaVersionsOneThroughThreeButOnlyTaskVersionOne() {
    assertEquals(
      "{\"type\":\"client_hello\",\"transportVersion\":1,\"clientInstanceId\":\"client_0123456789\",\"supportedQuotaVersions\":[1,2,3],\"supportedTaskVersions\":[1]}",
      SyncClientHelloWireContract.encode("client_0123456789"),
    )
  }

  @Test
  fun rejectsInvalidClientIdentity() {
    assertThrows(IllegalArgumentException::class.java) {
      SyncClientHelloWireContract.encode("short")
    }
  }
}
