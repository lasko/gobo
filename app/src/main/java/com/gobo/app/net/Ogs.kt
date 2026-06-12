package com.gobo.app.net

/** All OGS endpoints in one place for easy auditing. */
object Ogs {
    const val BASE = "https://online-go.com"

    // OAuth2
    const val AUTHORIZE = "$BASE/oauth2/authorize/"
    const val TOKEN = "$BASE/oauth2/token/"
    const val REVOKE = "$BASE/oauth2/revoke_token/"

    // Public OAuth client. PKCE means there is no client_secret to protect.
    // Register your own at https://online-go.com/oauth2/applications/
    const val CLIENT_ID = "uO0Qc1lAfvRq0IeOVgTiFtSzFzDagXZ53opHFUeG"
    const val REDIRECT_URI = "gobo://oauth"
    const val SCOPE = "read write"

    // REST
    const val UI_CONFIG = "$BASE/api/v1/ui/config"
    const val ME = "$BASE/api/v1/me"
    const val OVERVIEW = "$BASE/api/v1/ui/overview"

    /** Open challenge (any human may accept). */
    const val CHALLENGES = "$BASE/api/v1/challenges/"

    /** The current user's challenges (sent + received), not yet accepted. */
    const val MY_CHALLENGES = "$BASE/api/v1/me/challenges"

    /** A single challenge — DELETE to withdraw/cancel one you sent. */
    fun challenge(id: Long) = "$BASE/api/v1/me/challenges/$id"

    /** Direct challenge to a specific player (used for bots). */
    fun playerChallenge(playerId: Long) = "$BASE/api/v1/players/$playerId/challenge/"

    /** Single game detail — authoritative outcome/winner once a game has finished. */
    fun game(gameId: Long) = "$BASE/api/v1/games/$gameId"

    // Realtime WebSocket termination server
    const val SOCKET = "wss://online-go.com/socket"
}
