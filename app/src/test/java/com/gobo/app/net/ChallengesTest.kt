package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengesTest {

    @Test
    fun formatRankCoversKyuAndDan() {
        assertEquals("30k", formatRank(0.0))
        assertEquals("1k", formatRank(29.0))
        assertEquals("1d", formatRank(30.0))
        assertEquals("6d", formatRank(35.5)) // floored to 35
    }

    @Test
    fun parseActiveBotsFiltersDecliningBotsAndSortsByName() {
        val payload = """
            {
              "10": {"id":10,"username":"Zeta","ranking":5.0,"config":{
                "allowed_board_sizes":[9,13],
                "allowed_time_control_systems":["fischer"],
                "allow_ranked":true,"allow_unranked":false,
                "decline_new_challenges":false}},
              "20": {"id":20,"username":"Busy","ranking":30.0,"config":{
                "decline_new_challenges":true}},
              "30": {"id":30,"username":"alpha","ranking":0.0,"config":{"_config_version":0}}
            }
        """.trimIndent()

        val bots = parseActiveBots(Json.parseToJsonElement(payload))

        assertEquals(2, bots.size) // "Busy" filtered out
        // Sorted case-insensitively by username: alpha, Zeta
        assertEquals("alpha", bots[0].username)
        assertEquals("Zeta", bots[1].username)

        val zeta = bots[1]
        assertEquals(10L, zeta.id)
        assertEquals("25k", zeta.rank)
        assertEquals(listOf(9, 13), zeta.allowedSizes)
        assertEquals(listOf("fischer"), zeta.allowedSystems)
        assertTrue(zeta.allowsRanked)
        assertFalse(zeta.allowsUnranked)

        val alpha = bots[0]
        assertEquals("30k", alpha.rank)
        assertTrue(alpha.allowedSizes.isEmpty()) // no config restriction advertised
        assertTrue(alpha.allowsRanked)           // defaults when fields absent
    }

    @Test
    fun parseActiveBotsHandlesNonObject() {
        assertTrue(parseActiveBots(Json.parseToJsonElement("[]")).isEmpty())
    }

    private fun spec(
        name: String = "",
        komiAuto: Boolean = true,
        komi: Double = 6.5,
    ) = ChallengeSpec(
        opponent = Opponent.Human,
        name = name,
        boardSize = 13,
        ranked = false,
        color = "automatic",
        handicap = 2,
        komiAuto = komiAuto,
        komi = komi,
        rules = "japanese",
        speed = "live",
        timeControl = "fischer",
        timeParams = mapOf("initial_time" to 120, "time_increment" to 30, "max_time" to 300),
        disableAnalysis = false,
        pauseOnWeekends = false,
        isPrivate = false,
    )

    @Test
    fun buildChallengeBodyMapsCoreFields() {
        val body = buildChallengeBody(spec())

        assertEquals("automatic", body["challenger_color"]?.jsonPrimitive?.contentOrNull)
        assertEquals(-1000, body["min_ranking"]?.jsonPrimitive?.intOrNull)

        val game = body["game"]!!.jsonObject
        assertEquals("Friendly Match", game["name"]?.jsonPrimitive?.contentOrNull) // blank -> default
        assertEquals(13, game["width"]?.jsonPrimitive?.intOrNull)
        assertEquals(13, game["height"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, game["handicap"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, game["ranked"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("live", game["speed"]?.jsonPrimitive?.contentOrNull)
        assertEquals("fischer", game["time_control"]?.jsonPrimitive?.contentOrNull)

        val tcp = game["time_control_parameters"]!!.jsonObject
        assertEquals("fischer", tcp["system"]?.jsonPrimitive?.contentOrNull)
        assertEquals(120, tcp["initial_time"]?.jsonPrimitive?.intOrNull)
        assertEquals(30, tcp["time_increment"]?.jsonPrimitive?.intOrNull)
        assertEquals(300, tcp["max_time"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun buildChallengeBodyOmitsKomiWhenAutomatic() {
        val game = buildChallengeBody(spec(komiAuto = true))["game"]!!.jsonObject
        assertEquals("automatic", game["komi_auto"]?.jsonPrimitive?.contentOrNull)
        assertNull(game["komi"]) // not sent when automatic
    }

    @Test
    fun buildChallengeBodyIncludesCustomKomiAndName() {
        val game = buildChallengeBody(spec(name = "My Game", komiAuto = false, komi = 7.5))["game"]!!.jsonObject
        assertEquals("My Game", game["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("custom", game["komi_auto"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7.5, game["komi"]?.jsonPrimitive?.doubleOrNull!!, 0.0001)
    }
}
