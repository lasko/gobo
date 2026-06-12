package com.gobo.app.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * OGS realtime protocol over WebSocket.
 *
 * Wire format (per the goban protocol docs):
 *   client -> server:  [command: string, data: any, id?: number]
 *   server -> client:  [event_name: string, data: any]
 *                  or  [id: number, data?: any, error?: {...}]
 *
 * Privacy note: we deliberately do NOT send the optional analytics messages
 * the protocol allows (net/connects, net/timeout, net/route_latency,
 * net/unrecoverable_error). The authenticate payload is kept minimal — just
 * the jwt — with no device_id or user_agent.
 */
class OgsSocket {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ws: WebSocket? = null
    private val reqId = AtomicInteger(1)

    /** Server-to-client named events, emitted as (event, data) pairs. */
    private val _events = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val client = OkHttpClient.Builder().build()

    fun connect(onOpen: () -> Unit) {
        val req = Request.Builder().url(Ogs.SOCKET).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                val head = arr.getOrNull(0) ?: return
                // Named server event: [event_name, data]
                val name = (head as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name != null) {
                    val data = arr.getOrNull(1) ?: JsonNull
                    _events.tryEmit(name to data)
                }
                // Numeric-id responses are ignored here; add a callback map if you
                // need request/response correlation.
            }
        })
    }

    private fun send(command: String, data: JsonElement, withId: Boolean = false) {
        val msg = buildJsonArray {
            add(JsonPrimitive(command))
            add(data)
            if (withId) add(JsonPrimitive(reqId.getAndIncrement()))
        }
        ws?.send(msg.toString())
    }

    /** Step after fetching user_jwt from /api/v1/ui/config. */
    fun authenticate(userJwt: String) {
        send("authenticate", buildJsonObject {
            put("jwt", userJwt)
            put("client", "Gobo")
        })
    }

    /**
     * Connect to a game's realtime channel. [playerId] is required: OGS only pushes
     * the initial `game/<id>/gamedata` snapshot (board, players, clock, phase) when
     * the connect identifies the player. Omitting it leaves the board blank.
     */
    fun gameConnect(gameId: Long, playerId: Int, chat: Boolean = false) {
        send("game/connect", buildJsonObject {
            put("game_id", gameId)
            put("player_id", playerId)
            put("chat", chat)
        })
    }

    fun gameDisconnect(gameId: Long) {
        send("game/disconnect", buildJsonObject { put("game_id", gameId) })
    }

    /** move is a coordinate string like "qd"; "..": pass. */
    fun gameMove(gameId: Long, move: String) {
        send("game/move", buildJsonObject {
            put("game_id", gameId)
            put("move", move)
        })
    }

    fun gameResign(gameId: Long) {
        send("game/resign", buildJsonObject { put("game_id", gameId) })
    }

    /**
     * Send an in-game chat line. Identity comes from the prior [authenticate], so the
     * payload is just the game, text, the [moveNumber] it's attached to, and the channel
     * [type] ("main" for normal play). The server echoes it back as a `game/<id>/chat`
     * event, which is how the sender's own line appears.
     */
    fun sendChat(gameId: Long, body: String, moveNumber: Int, type: String = "main") {
        send("game/chat", buildGameChatMessage(gameId, body, moveNumber, type))
    }

    /**
     * Scoring phase: accept the given dead-stone set (OGS' 2-char-per-coord encoding).
     * The game concludes only once both players accept the identical [stones] + seki
     * mode, so [stones] must echo the set the server/opponent currently marks.
     */
    fun acceptRemovedStones(gameId: Long, playerId: Int, stones: String) {
        send("game/removed_stones/accept", buildJsonObject {
            put("game_id", gameId)
            put("player_id", playerId)
            put("stones", stones)
            put("strict_seki_mode", false)
        })
    }

    /** Scoring phase: reject the proposed removal and resume play. */
    fun rejectRemovedStones(gameId: Long, playerId: Int) {
        send("game/removed_stones/reject", buildJsonObject {
            put("game_id", gameId)
            put("player_id", playerId)
        })
    }

    /** Keepalive ping. Send roughly every 10s while connected. */
    fun ping() {
        send("net/ping", buildJsonObject {
            put("client", System.currentTimeMillis())
            put("drift", 0)
            put("latency", 0)
        })
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }
}
