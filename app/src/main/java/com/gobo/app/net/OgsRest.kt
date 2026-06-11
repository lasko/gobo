package com.gobo.app.net

import com.gobo.app.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class UiConfig(val userJwt: String, val playerId: Int, val username: String)

data class GameSummary(
    val id: Long,
    val boardSize: Int,
    val blackUsername: String,
    val whiteUsername: String,
    val myTurn: Boolean,
)

/** Authoritative end state for a finished game. [winnerId] is an OGS player id. */
data class GameResult(val outcome: String, val winnerId: Int?)

class OgsRest(private val store: TokenStore) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchUiConfig(): Result<UiConfig> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext Result.failure(
            IllegalStateException("Not logged in")
        )
        get(Ogs.UI_CONFIG, token) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val jwt = root["user_jwt"]?.jsonPrimitive?.contentOrNull
                ?: error("no user_jwt in config")
            val user = root["user"]?.jsonObject ?: JsonObject(emptyMap())
            UiConfig(
                userJwt = jwt,
                playerId = user["id"]?.jsonPrimitive?.intOrNull ?: 0,
                username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    suspend fun fetchActiveGames(myPlayerId: Int): Result<List<GameSummary>> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext Result.failure(
            IllegalStateException("Not logged in")
        )
        get(Ogs.OVERVIEW, token) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            val games = root["active_games"]?.jsonArray ?: return@get emptyList()
            games.mapNotNull { el ->
                val g = el.jsonObject
                val id = g["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val size = g["width"]?.jsonPrimitive?.intOrNull ?: 19
                val players = g["players"]?.jsonObject
                val blackPlayer = players?.get("black")?.jsonObject
                val whitePlayer = players?.get("white")?.jsonObject
                val blackName = blackPlayer?.get("username")?.jsonPrimitive?.contentOrNull ?: "Black"
                val whiteName = whitePlayer?.get("username")?.jsonPrimitive?.contentOrNull ?: "White"
                // clock.current_player holds the player ID whose turn it is
                val gameJson = g["json"]?.jsonObject
                val currentPlayer = gameJson
                    ?.get("clock")?.jsonObject
                    ?.get("current_player")?.jsonPrimitive?.intOrNull ?: 0
                GameSummary(id, size, blackName, whiteName, myTurn = currentPlayer == myPlayerId)
            }
        }
    }

    /**
     * Fetch the authoritative result of a (usually just-finished) game. The realtime
     * `phase` broadcast tells us a game ended but not who won; this fills that in.
     */
    suspend fun fetchGameResult(gameId: Long): Result<GameResult> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext Result.failure(
            IllegalStateException("Not logged in")
        )
        get(Ogs.game(gameId), token) { body ->
            val root = json.parseToJsonElement(body).jsonObject
            GameResult(
                outcome = root["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                winnerId = root["winner"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /**
     * Create a challenge. For [Opponent.Human] this posts an open seek that any
     * player may accept; for [Opponent.Computer] it challenges the bot directly,
     * which it auto-accepts almost immediately.
     */
    suspend fun createChallenge(spec: ChallengeSpec): Result<ChallengeResult> =
        withContext(Dispatchers.IO) {
            val token = store.accessToken ?: return@withContext Result.failure(
                IllegalStateException("Not logged in")
            )
            val vsBot = spec.opponent is Opponent.Computer
            val url = when (val o = spec.opponent) {
                is Opponent.Computer -> Ogs.playerChallenge(o.bot.id)
                Opponent.Human -> Ogs.CHALLENGES
            }
            post(url, token, buildChallengeBody(spec)) { responseBody ->
                val root = json.parseToJsonElement(responseBody).jsonObject
                ChallengeResult(
                    challengeId = root["challenge"]?.jsonPrimitive?.longOrNull ?: 0L,
                    gameId = root["game"]?.jsonPrimitive?.longOrNull ?: 0L,
                    vsBot = vsBot,
                )
            }
        }

    private fun <T> post(
        url: String,
        token: String,
        body: JsonElement,
        parse: (String) -> T,
    ): Result<T> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            parse(text)
        }
    }

    private fun <T> get(url: String, token: String, parse: (String) -> T): Result<T> =
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
                parse(resp.body?.string() ?: error("empty body"))
            }
        }
}
