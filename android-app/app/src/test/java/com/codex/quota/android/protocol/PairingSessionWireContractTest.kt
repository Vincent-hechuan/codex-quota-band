package com.codex.quota.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingSessionWireContractTest {
  @Test
  fun requestAndSuccessUseOnlyTheClosedPairingFields() {
    val request = PairingSessionWireContract.encodeRequest("123456", "client_0123456789")
    assertEquals(
      "{\"type\":\"pair_request\",\"protocolVersion\":1,\"code\":\"123456\",\"clientInstanceId\":\"client_0123456789\"}",
      request,
    )
    assertEquals(
      "ab".repeat(32),
      PairingSessionWireContract.decodeSuccess(
        "{\"type\":\"pair_success\",\"protocolVersion\":1,\"phoneToken\":\"${"ab".repeat(32)}\"}",
      ),
    )
  }

  @Test
  fun rejectsUnknownFieldsAndMalformedTokens() {
    assertThrows(Exception::class.java) {
      PairingSessionWireContract.decodeSuccess(
        "{\"type\":\"pair_success\",\"protocolVersion\":1,\"phoneToken\":\"${"ab".repeat(32)}\",\"prompt\":\"private\"}",
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      PairingSessionWireContract.decodeSuccess(
        "{\"type\":\"pair_success\",\"protocolVersion\":1,\"phoneToken\":\"SHORT\"}",
      )
    }
  }
}
