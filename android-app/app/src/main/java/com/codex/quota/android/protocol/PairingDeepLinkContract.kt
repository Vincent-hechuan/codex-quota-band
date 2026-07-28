package com.codex.quota.android.protocol

import android.net.Uri
import android.util.Base64

object PairingDeepLinkContract {
  fun decode(uri: Uri): PairingOffer {
    require(uri.scheme == "codexquota" && uri.host == "pair") { "invalid pairing link" }
    val encoded = requireNotNull(uri.getQueryParameter("offer")) { "missing pairing offer" }
    val payload = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    require(payload.size <= 4 * 1024) { "pairing offer is too large" }
    return PairingOfferWireContract.decode(payload.toString(Charsets.UTF_8))
  }
}
