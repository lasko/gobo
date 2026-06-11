package com.gobo.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gobo.app.net.Ogs
import net.openid.appauth.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * OAuth2 Authorization Code flow with PKCE, via AppAuth.
 *
 * AppAuth launches a Chrome Custom Tab rather than an embedded WebView, so
 * the user's OGS password is never seen by this app, and the code_verifier
 * never leaves the device. We request only the `read write` scopes the app
 * actually uses.
 */
class AuthManager(context: Context, private val store: TokenStore) {

    private val service = AuthorizationService(context.applicationContext)

    private val config = AuthorizationServiceConfiguration(
        Uri.parse(Ogs.AUTHORIZE),
        Uri.parse(Ogs.TOKEN)
    )

    /** Build the intent that opens the OGS consent screen. PKCE is automatic. */
    fun buildAuthRequestIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            config,
            Ogs.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(Ogs.REDIRECT_URI)
        ).setScope(Ogs.SCOPE).build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Handle the redirect, exchange the code for tokens (sends code_verifier). */
    suspend fun handleRedirect(data: Intent): Result<Unit> {
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) return Result.failure(ex ?: IllegalStateException("No auth response"))

        return suspendCoroutine { cont ->
            // No client secret: public client, PKCE verifier supplied by AppAuth.
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, tokenEx ->
                if (tokenResp != null) {
                    store.accessToken = tokenResp.accessToken
                    store.refreshToken = tokenResp.refreshToken
                    tokenResp.accessTokenExpirationTime?.let {
                        store.expiresAtEpochSec = it / 1000
                    }
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(tokenEx ?: IllegalStateException("Token exchange failed")))
                }
            }
        }
    }

    fun dispose() = service.dispose()
}
