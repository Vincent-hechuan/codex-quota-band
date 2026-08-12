package com.codex.quota.android.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.codex.quota.android.protocol.PairingDiscoveryWireContract
import com.codex.quota.android.ui.CodexQuotaTheme

class PairingActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CodexQuotaTheme {
        CodexPairingFlow(
          onClose = { finish() },
          onPairingLink = { link ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAIRING_LINK, link))
            finish()
          },
          onManualPairing = { discovery, code ->
            setResult(
              Activity.RESULT_OK,
              Intent()
                .putExtra(EXTRA_DISCOVERY_PAYLOAD, PairingDiscoveryWireContract.encode(discovery))
                .putExtra(EXTRA_PAIRING_CODE, code),
            )
            finish()
          },
        )
      }
    }
  }

  companion object {
    const val EXTRA_PAIRING_LINK = "pairing_link"
    const val EXTRA_DISCOVERY_PAYLOAD = "pairing_discovery"
    const val EXTRA_PAIRING_CODE = "pairing_code"
  }
}
