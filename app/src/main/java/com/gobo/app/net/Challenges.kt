package com.gobo.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.floor

/** A bot that is currently online and accepting challenges. */
data class Bot(
    val id: Long,
    val username: String,
    val rank: String,
    /** Empty means the bot did not advertise a restriction (treat as any). */
    val allowedSizes: List<Int>,
    val allowedSystems: List<String>,
    val allowsRanked: Boolean,
    val allowsUnranked: Boolean,
)

/** Who we are challenging. */
sealed interface Opponent {
    /** Open seek any human can accept. */
    data object Human : Opponent
    data class Computer(val bot: Bot) : Opponent
}

/** Full set of options the New Game form can specify. */
data class ChallengeSpec(
    val opponent: Opponent,
    val name: String,
    val boardSize: Int,
    val ranked: Boolean,
    /** "automatic" | "black" | "white" */
    val color: String,
    val handicap: Int,
    val komiAuto: Boolean,
    val komi: Double,
    /** japanese | chinese | korean | aga | ing | nz */
    val rules: String,
    /** blitz | live | correspondence */
    val speed: String,
    /** fischer | byoyomi | simple */
    val timeControl: String,
    val timeParams: Map<String, Int>,
    val disableAnalysis: Boolean,
    val pauseOnWeekends: Boolean,
    val isPrivate: Boolean,
)

/** Result of POSTing a challenge. */
data class ChallengeResult(val challengeId: Long, val gameId: Long, val vsBot: Boolean)

/**
 * Build the OGS challenge request body from a [ChallengeSpec]. Pure (no network /
 * Android deps) so it can be unit-tested directly.
 */
fun buildChallengeBody(spec: ChallengeSpec): JsonObject = buildJsonObject {
    put("initialized", false)
    // Wide range so an open challenge is acceptable to any rank.
    put("min_ranking", -1000)
    put("max_ranking", 1000)
    put("challenger_color", spec.color)
    put("aga_ranked", false)
    putJsonObject("game") {
        put("name", spec.name.ifBlank { "Friendly Match" })
        put("rules", spec.rules)
        put("ranked", spec.ranked)
        put("width", spec.boardSize)
        put("height", spec.boardSize)
        put("handicap", spec.handicap)
        put("komi_auto", if (spec.komiAuto) "automatic" else "custom")
        if (!spec.komiAuto) put("komi", spec.komi)
        put("disable_analysis", spec.disableAnalysis)
        put("pause_on_weekends", spec.pauseOnWeekends)
        put("private", spec.isPrivate)
        put("speed", spec.speed)
        put("time_control", spec.timeControl)
        putJsonObject("time_control_parameters") {
            put("system", spec.timeControl)
            put("speed", spec.speed)
            put("pause_on_weekends", spec.pauseOnWeekends)
            spec.timeParams.forEach { (k, v) -> put(k, v) }
        }
    }
}

/** Convert an OGS rank index (≈0–38) into a kyu/dan label for display. */
fun formatRank(ranking: Double): String {
    val i = floor(ranking).toInt()
    return if (i >= 30) "${i - 29}d" else "${30 - i}k"
}

/**
 * Parse the one-shot `active-bots` socket broadcast into a list of bots that are
 * online and not declining challenges. The payload is an object keyed by bot id.
 */
fun parseActiveBots(data: JsonElement): List<Bot> {
    val obj = data as? JsonObject ?: return emptyList()
    return obj.values.mapNotNull { el ->
        val b = el as? JsonObject ?: return@mapNotNull null
        val id = b["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val name = b["username"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val cfg = b["config"]?.jsonObject
        if (cfg?.get("decline_new_challenges")?.jsonPrimitive?.booleanOrNull == true) {
            return@mapNotNull null
        }
        val ranking = b["ranking"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val sizes = cfg?.get("allowed_board_sizes")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
        val systems = cfg?.get("allowed_time_control_systems")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        Bot(
            id = id,
            username = name,
            rank = formatRank(ranking),
            allowedSizes = sizes,
            allowedSystems = systems,
            allowsRanked = cfg?.get("allow_ranked")?.jsonPrimitive?.booleanOrNull ?: true,
            allowsUnranked = cfg?.get("allow_unranked")?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }.sortedBy { it.username.lowercase() }
}
