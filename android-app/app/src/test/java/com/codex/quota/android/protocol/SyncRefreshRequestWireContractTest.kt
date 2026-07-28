package com.codex.quota.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRefreshRequestWireContractTest {
  @Test
  fun `refresh request is closed and bound to the negotiated connection`() {
    assertEquals(
      "{\"type\":\"refresh_request\",\"transportVersion\":1,\"connectionId\":\"connection_0123456789\",\"scope\":\"quota\"}",
      SyncRefreshRequestWireContract.encode("connection_0123456789"),
    )
  }
}
