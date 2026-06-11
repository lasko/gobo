package com.gobo.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores OAuth tokens encrypted at rest. Nothing else is persisted —
 * no usernames, no game history, no identifiers beyond what's needed to
 * stay logged in.
 */
class TokenStore(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "gobo_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    var expiresAtEpochSec: Long
        get() = prefs.getLong("expires_at", 0L)
        set(v) = prefs.edit().putLong("expires_at", v).apply()

    val isLoggedIn: Boolean get() = accessToken != null

    /** Full wipe on logout. */
    fun clear() = prefs.edit().clear().apply()
}
