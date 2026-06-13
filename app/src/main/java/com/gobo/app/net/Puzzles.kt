package com.gobo.app.net

import com.gobo.app.board.OgsCoord
import com.gobo.app.board.Stone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
    /** OGS rank ints (>=30 dan, else kyu) bracketing the collection's difficulty; see [formatDifficulty]. */
    val minRank: Int,
    val maxRank: Int,
    /** Average puzzle rating (0..5) and how many ratings it's based on. */
    val rating: Double,
    val ratingCount: Int,
    val viewCount: Int,
    val solvedCount: Int,
    /** ISO-8601 creation timestamp from the API; render via [formatPuzzleDate]. */
    val created: String,
    val ownerName: String,
    val startingPuzzleId: Long,
    val width: Int,
    val height: Int,
)

/**
 * How to order the collection browse list. [key] is the OGS `ordering` field; [descending] is the
 * sensible default direction (most popular/best first for counts & rating, ascending for name &
 * easiest-first difficulty). The screen lets the user flip direction.
 */
enum class PuzzleSort(val label: String, val key: String, val descending: Boolean) {
    Rating("Rating", "rating", true),
    Puzzles("Puzzles", "puzzle_count", true),
    Difficulty("Difficulty", "min_rank", false),
    Views("Views", "view_count", true),
    Solved("Solved", "solved_count", true),
    Newest("Created", "created", true),
    Name("Name", "name", false),
}

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
 * Render a collection's difficulty as a rank or rank range ("30k", "30k–5d") from OGS' rank ints,
 * reusing [formatRank]. Collapses to a single rank when both ends are equal.
 */
fun formatDifficulty(minRank: Int, maxRank: Int): String {
    val lo = formatRank(minRank.toDouble())
    val hi = formatRank(maxRank.toDouble())
    return if (lo == hi) lo else "$lo–$hi"
}

/**
 * Compact a count the way the OGS puzzle browser does: "893", "1.3K", "48.1K", "5.0M" (one decimal
 * for K/M, kept even when ".0"). Negatives are clamped to 0.
 */
fun formatCount(n: Int): String = when {
    n < 1_000 -> n.coerceAtLeast(0).toString()
    n < 1_000_000 -> "%.1fK".format(java.util.Locale.ROOT, n / 1_000.0)
    else -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000.0)
}

private val SHORT_MONTHS =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Render the date portion of an ISO-8601 timestamp ("2025-11-04T08:23:13Z") as "Nov 2025". Returns
 * an empty string when the input has no parseable yyyy-MM-dd prefix (so the UI can omit it).
 */
fun formatPuzzleDate(iso: String): String {
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return ""
    val year = parts[0].toIntOrNull() ?: return ""
    val month = parts[1].toIntOrNull() ?: return ""
    if (month !in 1..12 || parts[0].length != 4) return ""
    return "${SHORT_MONTHS[month - 1]} $year"
}

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
            rating = o["rating"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            ratingCount = o["rating_count"]?.jsonPrimitive?.intOrNull ?: 0,
            viewCount = o["view_count"]?.jsonPrimitive?.intOrNull ?: 0,
            solvedCount = o["solved_count"]?.jsonPrimitive?.intOrNull ?: 0,
            created = o["created"].asContent(),
            ownerName = (o["owner"] as? JsonObject)?.get("username").asTrimmed(),
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

/**
 * The move(s) that progress toward the solution from [current] — every listed branch *not* marked
 * wrong (a correct/solving move or a correct continuation). Drives the puzzle "hint" highlight.
 * Empty when the node has no non-wrong continuation listed.
 */
fun puzzleHints(current: PuzzleNode): List<Pair<Int, Int>> =
    current.branches.filter { !it.wrong }.map { it.x to it.y }

private fun resultsArray(body: String): JsonArray? =
    when (val root = puzzleJson.parseToJsonElement(body)) {
        is JsonArray -> root
        is JsonObject -> root["results"] as? JsonArray
        else -> null
    }

private fun kotlinx.serialization.json.JsonElement?.asContent(): String =
    this?.jsonPrimitive?.contentOrNull.orEmpty()

private fun kotlinx.serialization.json.JsonElement?.asTrimmed(): String = asContent().trim()
