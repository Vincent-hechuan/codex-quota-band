package com.codex.quota.android.security

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.security.SecureRandom

class ConnectionIdentityStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun clientInstanceId(): String {
    preferences.getString(KEY_CLIENT_INSTANCE_ID, null)?.let { return it }
    val random = ByteArray(16).also(SecureRandom()::nextBytes)
    val generated = random.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    random.fill(0)
    preferences.edit { putString(KEY_CLIENT_INSTANCE_ID, generated) }
    return generated
  }

  fun saveSyncEndpoint(endpoint: URI) {
    requireValidSyncEndpoint(endpoint)
    preferences.edit { putString(KEY_SYNC_ENDPOINT, endpoint.toString()) }
  }

  fun loadSyncEndpoint(): URI? {
    val stored = preferences.getString(KEY_SYNC_ENDPOINT, null) ?: return null
    return runCatching { URI(stored).also(::requireValidSyncEndpoint) }
      .getOrElse {
        preferences.edit { remove(KEY_SYNC_ENDPOINT) }
        null
      }
  }

  fun clearSyncEndpoint() {
    preferences.edit { remove(KEY_SYNC_ENDPOINT) }
  }

  companion object {
    internal fun requireValidSyncEndpoint(uri: URI) {
      require(
        uri.scheme == "wss" &&
          uri.rawUserInfo == null &&
          uri.rawQuery == null &&
          uri.rawFragment == null &&
          uri.rawPath == "/sync" &&
          uri.port in 1..65_535 &&
          isPrivateIpv4(uri.host),
      ) {
        "invalid sync endpoint"
      }
    }

    private fun isPrivateIpv4(host: String?): Boolean {
      val octets = host?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
      if (octets.size != 4 || octets.any { it !in 0..255 }) return false
      return octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 169 && octets[1] == 254)
    }

    private const val PREFERENCES_NAME = "connection-identity"
    private const val KEY_CLIENT_INSTANCE_ID = "client-instance-id"
    private const val KEY_SYNC_ENDPOINT = "sync-endpoint"
  }
}
