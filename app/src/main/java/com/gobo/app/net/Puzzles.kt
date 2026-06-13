package com.gobo.app.net

import com.gobo.app.board.OgsCoord
import com.gobo.app.board.Stone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsing + solving logic for OGS puzzles (tsumego). Kept as top-level functions with no
 * Android / OkHttp / Compose deps so the JSON shapes and the solution-tree walk — the parts most
 * likely to drift or hide a bug — are unit-tested directly. [OgsRest] owns the HTTP; the
 * `Puzzle*ViewModel`s own the board and call these.
 */
private val puzzleJson = Json { ignoreUnknownKeys = true }

/** A puzzle collection as shown in the browse list. [startingPuzzleId] is the first puzzle to open. */
data class PuzzleCollection(
    val id: Long,
    val name: String,
    val puzzleCount: Int,
    val minRank: Int,
    val maxRank: Int,
    val startingPuzzleId: Long,
    val width: Int,
    val height: Int,
)

/** An entry in a collection's ordered puzzle list (`/puzzles/{id}/collection_summary`), for prev/next. */
data class PuzzleRef(val id: Long, val name: String)

/**
 * A node in a puzzle's solution tree. The root is a no-move sentinel (x = y = -1); every other node
 * is a move at (x, y). Tree levels alternate player → opponent → player … [correct]/[wrong] flag a
 * solving / losing move. OGS serializes an *empty* `branches` as a string ("" or whitespace) rather
 * than `[]`, which [parsePuzzle] normalizes to an empty list.
 */
data class PuzzleNode(
    val x: Int,
    val y: Int,
    val branches: List<PuzzleNode>,
    val correct: Boolean = false,
    val wrong: Boolean = false,
)

/** A single puzzle: the starting position plus the solution tree to solve against. */
data class Puzzle(
    val id: Long,
    val name: String,
    val description: String,
    val width: Int,
    val height: Int,
    /** Pre-placed stones (intersection coords), decoded from OGS' packed coordinate encoding. */
    val initialBlack: List<Pair<Int, Int>>,
    val initialWhite: List<Pair<Int, Int>>,
    /** The colour the human plays; the opponent (which auto-replies from the tree) plays the other. */
    val playerColor: Stone,
    val moveTree: PuzzleNode,
    val collectionId: Long,
)

/**
 * Decode OGS' packed coordinate string ("dadbdc" = (d,a),(d,b),(d,c)…) into intersections. Pass
 * placeholders ("..") and any trailing odd character are skipped (both decode to null).
 */
fun decodePuzzleStones(packed: String): List<Pair<Int, Int>> =
    packed.chunked(2).mapNotNull { OgsCoord.decode(it) }

/**
 * Parse the `/puzzles/collections/` browse list. Accepts the paginated `{results:[…]}` wrapper or a
 * bare array; entries without an id are skipped. Defaults keep a sparse entry renderable.
 */
fun parsePuzzleCollections(body: String): List<PuzzleCollection> {
    val results = resultsArray(body) ?: return emptyList()
    return results.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val starting = o["starting_puzzle"] as? JsonObject
        PuzzleCollection(
            id = id,
            name = o["name"].asTrimmed().ifBlank { "Untitled" },
            puzzleCount = o["puzzle_count"]?.jsonPrimitive?.intOrNull ?: 0,
            minRank = o["min_rank"]?.jsonPrimitive?.intOrNull ?: 0,
            maxRank = o["max_rank"]?.jsonPrimitive?.intOrNull ?: 0,
            startingPuzzleId = starting?.get("id")?.jsonPrimitive?.longOrNull ?: 0L,
            width = starting?.get("width")?.jsonPrimitive?.intOrNull ?: 19,
            height = starting?.get("height")?.jsonPrimitive?.intOrNull ?: 19,
        )
    }
}

/** Parse a collection's ordered puzzle list (`/puzzles/{id}/collection_summary`), for prev/next nav. */
fun parseCollectionSummary(body: String): List<PuzzleRef> {
    val arr = resultsArray(body) ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        PuzzleRef(id, o["name"].asTrimmed().ifBlank { "Puzzle" })
    }
}

/** Parse a single `/puzzles/{id}`. Returns null if the body lacks an id or the `puzzle` block. */
fun parsePuzzle(body: String): Puzzle? {
    val root = puzzleJson.parseToJsonElement(body) as? JsonObject ?: return null
    val id = root["id"]?.jsonPrimitive?.longOrNull ?: return null
    val p = root["puzzle"] as? JsonObject ?: return null
    val initial = p["initial_state"] as? JsonObject
    val black = decodePuzzleStones(initial?.get("black").asContent())
    val white = decodePuzzleStones(initial?.get("white").asContent())
    // initial_player names the colour the human moves; default Black if absent/odd.
    val playerColor = if (p["initial_player"].asContent() == "white") Stone.WHITE else Stone.BLACK
    return Puzzle(
        id = id,
        name = root["name"].asTrimmed().ifBlank { "Puzzle" },
        description = p["puzzle_description"].asTrimmed(),
        width = p["width"]?.jsonPrimitive?.intOrNull ?: 19,
        height = p["height"]?.jsonPrimitive?.intOrNull ?: 19,
        initialBlack = black,
        initialWhite = white,
        playerColor = playerColor,
        moveTree = parseMoveNode(p["move_tree"] as? JsonObject) ?: PuzzleNode(-1, -1, emptyList()),
        collectionId = p["puzzle_collection"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

private fun parseMoveNode(o: JsonObject?): PuzzleNode? {
    if (o == null) return null
    // OGS serializes an empty branch list as a string ("" / whitespace), not [] — so anything that
    // isn't a JsonArray means "no children".
    val branches = (o["branches"] as? JsonArray)
        ?.mapNotNull { parseMoveNode(it as? JsonObject) }
        ?: emptyList()
    return PuzzleNode(
        x = o["x"]?.jsonPrimitive?.intOrNull ?: -1,
        y = o["y"]?.jsonPrimitive?.intOrNull ?: -1,
        branches = branches,
        correct = o["correct_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
        wrong = o["wrong_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

/** Outcome of applying a player's tap to the puzzle's current tree node. See [puzzleStep]. */
sealed interface PuzzleStep {
    /** The tap matched no listed branch — reject it (flash invalid), no state change. */
    data object OffTree : PuzzleStep

    /**
     * A correct, non-terminal move: place [playerMove], then the opponent's [opponentMove]
     * (null when the line ends without a reply), advancing to [next] for the player's following tap.
     */
    data class Continue(
        val playerMove: Pair<Int, Int>,
        val opponentMove: Pair<Int, Int>?,
        val next: PuzzleNode,
    ) : PuzzleStep

    /** The solving move: place [playerMove]; the puzzle is solved. */
    data class Solved(val playerMove: Pair<Int, Int>) : PuzzleStep

    /** A losing move: place [playerMove]; the puzzle is failed (offer a retry). */
    data class Wrong(val playerMove: Pair<Int, Int>) : PuzzleStep
}

/**
 * Decide what a player tap at (x, y) does against [current], the active tree node. Pure: it mutates
 * nothing — the caller applies the returned move(s) to its own board (so captures render). A tap
 * matching no branch is [PuzzleStep.OffTree]; a branch marked `wrong` fails; one marked `correct`
 * (or a dead-end line with no reply) solves; otherwise the opponent plays its first listed reply and
 * we advance to that node for the next tap.
 */
fun puzzleStep(current: PuzzleNode, x: Int, y: Int): PuzzleStep {
    val child = current.branches.firstOrNull { it.x == x && it.y == y } ?: return PuzzleStep.OffTree
    if (child.wrong) return PuzzleStep.Wrong(x to y)
    if (child.correct) return PuzzleStep.Solved(x to y)
    // No marked outcome: the opponent replies with its first listed continuation, if any. A
    // continuation with no reply is the end of a non-wrong line, which we treat as solved.
    val opponent = child.branches.firstOrNull() ?: return PuzzleStep.Solved(x to y)
    return PuzzleStep.Continue(x to y, opponent.x to opponent.y, opponent)
}

private fun resultsArray(body: String): JsonArray? =
    when (val root = puzzleJson.parseToJsonElement(body)) {
        is JsonArray -> root
        is JsonObject -> root["results"] as? JsonArray
        else -> null
    }

private fun kotlinx.serialization.json.JsonElement?.asContent(): String =
    this?.jsonPrimitive?.contentOrNull.orEmpty()

private fun kotlinx.serialization.json.JsonElement?.asTrimmed(): String = asContent().trim()
