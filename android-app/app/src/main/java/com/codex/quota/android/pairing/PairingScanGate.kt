package com.codex.quota.android.pairing

import java.net.URI

class PairingScanGate {
  private var accepted = false

  fun accept(rawValue: String?): String? {
    if (accepted || rawValue.isNullOrBlank()) return null
    val uri = runCatching { URI(rawValue) }.getOrNull() ?: return null
    if (uri.scheme != "codexquota" || uri.host != "pair") return null
    accepted = true
    return rawValue
  }
}
