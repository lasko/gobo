package com.gobo.app.board

enum class Stone { EMPTY, BLACK, WHITE }

/**
 * Why a prospective move is (il)legal, for immediate invalid-tap feedback. We
 * only model the cases OGS rejects under *every* ruleset — occupied points,
 * suicide, and simple ko — so flashing one locally can never block a move the
 * server would actually accept. (Positional superko is deliberately omitted: it
 * is legal under simple-ko rulesets, so enforcing it client-side could reject a
 * legal move. The server stays authoritative for that.)
 */
enum class MoveLegality { LEGAL, OCCUPIED, SUICIDE, KO }

/**
 * Board state with full capture logic. OGS is still the authoritative source
 * of truth (we apply its board[][] snapshots directly), but incremental
 * game/move events are handled locally so captures render without a round-trip.
 */
class BoardState(val size: Int = 19) {
    val grid = Array(size) { Array(size) { Stone.EMPTY } }

    fun set(x: Int, y: Int, s: Stone) {
        if (x in 0 until size && y in 0 until size) grid[y][x] = s
    }

    fun clear() {
        for (row in grid) row.fill(Stone.EMPTY)
    }

    /** Place a stone, remove any captured opponent groups, and return how many stones were captured. */
    fun applyMove(x: Int, y: Int, color: Stone): Int = place(x, y, color).size

    /**
     * Place a stone and remove any captured opponent groups, returning the exact
     * intersections that were captured (already cleared). Callers that only need
     * the count use [applyMove]; the positions are needed to detect a ko.
     */
    fun place(x: Int, y: Int, color: Stone): List<Pair<Int, Int>> {
        set(x, y, color)
        val opponent = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
        val captured = mutableListOf<Pair<Int, Int>>()
        for ((nx, ny) in neighbors(x, y)) {
            // A group removed via an earlier neighbor is already EMPTY here, so it
            // is neither re-scanned nor double-counted.
            if (grid[ny][nx] == opponent) {
                val group = groupAt(nx, ny)
                if (!groupHasLiberty(group)) {
                    group.forEach { (gx, gy) -> grid[gy][gx] = Stone.EMPTY }
                    captured += group
                }
            }
        }
        return captured
    }

    /**
     * Test whether [color] may play at (x, y) without mutating the board. [koPoint],
     * when set, is the single intersection forbidden by the simple ko rule (the point
     * a one-stone capture just vacated — see [koPointAfter]). Mirrors the always-illegal
     * cases OGS rejects so an illegal tap can flash immediately instead of round-tripping.
     */
    fun legality(x: Int, y: Int, color: Stone, koPoint: Pair<Int, Int>? = null): MoveLegality {
        if (x !in 0 until size || y !in 0 until size) return MoveLegality.OCCUPIED
        if (grid[y][x] != Stone.EMPTY) return MoveLegality.OCCUPIED
        if (koPoint != null && koPoint.first == x && koPoint.second == y) return MoveLegality.KO
        // Simulate on a scratch copy so the live board is untouched. A move that
        // captures can never be suicide; otherwise the played group must end with
        // at least one liberty.
        val scratch = copy()
        val captured = scratch.place(x, y, color)
        if (captured.isEmpty() && !scratch.groupHasLiberty(scratch.groupAt(x, y))) {
            return MoveLegality.SUICIDE
        }
        return MoveLegality.LEGAL
    }

    /**
     * The point forbidden to the opponent by the simple ko rule, given that [color]
     * has just played at (x, y) capturing [captured] (call after [place]). A ko arises
     * only when exactly one stone was taken and the played stone is itself a lone stone
     * with a single liberty — then the opponent may not immediately retake at the vacated
     * point. Returns that point, or null when no ko arises.
     */
    fun koPointAfter(x: Int, y: Int, captured: List<Pair<Int, Int>>): Pair<Int, Int>? {
        if (captured.size != 1) return null
        val group = groupAt(x, y)
        if (group.size != 1) return null
        val liberties = neighbors(x, y).count { (nx, ny) -> grid[ny][nx] == Stone.EMPTY }
        return if (liberties == 1) captured.single() else null
    }

    private fun copy(): BoardState = BoardState(size).also { dst ->
        for (y in 0 until size) for (x in 0 until size) dst.grid[y][x] = grid[y][x]
    }

    private fun groupAt(startX: Int, startY: Int): Set<Pair<Int, Int>> {
        val color = grid[startY][startX]
        val visited = mutableSetOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(startX to startY)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            if (!visited.add(pos)) continue
            val (x, y) = pos
            neighbors(x, y).filter { (nx, ny) -> grid[ny][nx] == color }.forEach { queue.add(it) }
        }
        return visited
    }

    private fun groupHasLiberty(group: Set<Pair<Int, Int>>): Boolean =
        group.any { (x, y) -> neighbors(x, y).any { (nx, ny) -> grid[ny][nx] == Stone.EMPTY } }

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> =
        listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)
            .filter { (nx, ny) -> nx in 0 until size && ny in 0 until size }
}

/**
 * OGS encodes a move as a two-letter string: column then row, each 'a'..'s'
 * (0-indexed). ".." means pass. This matches the wire encoding used in
 * game/move and game/gamedata events.
 */
object OgsCoord {
    fun encode(x: Int, y: Int): String {
        if (x < 0 || y < 0) return ".."
        return "${('a' + x)}${('a' + y)}"
    }

    fun decode(s: String): Pair<Int, Int>? {
        if (s.length < 2 || s == "..") return null
        return (s[0] - 'a') to (s[1] - 'a')
    }
}
