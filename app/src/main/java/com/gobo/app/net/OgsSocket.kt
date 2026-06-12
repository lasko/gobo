package com.gobo.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** Connection lifecycle; the UI shows a reconnecting banner while not [ConnectionState.Connected]. */
    private val _connection = MutableStateFlow(ConnectionState.Connecting)
    val connection = _connection.asStateFlow()

    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Re-run on every (re)connection — re-authenticates and re-connects the game to resync. */
    private var onOpenCallback: (() -> Unit)? = null
    /** True once [close] is called, so a deliberate teardown isn't treated as a drop to recover from. */
    @Volatile private var manuallyClosed = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    /**
     * Open the game socket. [onOpen] (authenticate + game/connect) is stored and re-invoked on every
     * reconnection so a dropped connection silently resyncs: the re-sent `game/connect` makes the
     * server push a fresh `gamedata` snapshot, which rebuilds the whole board state.
     */
    fun connect(onOpen: () -> Unit) {
        onOpenCallback = onOpen
        manuallyClosed = false
        reconnectAttempt = 0
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url(Ogs.SOCKET).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _connection.value = ConnectionState.Connected
                onOpenCallback?.invoke()
            }

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

            // A network failure or a non-clean close (anything but our own code 1000) is a drop —
            // recover from it. OkHttp won't reuse this WebSocket, so we open a fresh one.
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                scheduleReconnect()

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        if (reconnectJob?.isActive == true) return // a retry is already pending
        _connection.value = ConnectionState.Reconnecting
        val wait = reconnectDelayMs(reconnectAttempt)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (!manuallyClosed) openSocket()
        }
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
     * Ask to take back to [moveNumber] (the current move count). Identity comes from the prior
     * [authenticate]; the opponent (a bot, in our case) must accept before anything reverts —
     * we act on the resulting `game/<id>/undo_accepted` event, not optimistically.
     */
    fun requestUndo(gameId: Long, moveNumber: Int) {
        send("game/undo/request", buildUndoRequest(gameId, moveNumber))
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
        manuallyClosed = true
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _connection.value = ConnectionState.Disconnected
    }
}
