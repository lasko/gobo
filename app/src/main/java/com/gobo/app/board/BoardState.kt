package com.gobo.app.board

enum class Stone { EMPTY, BLACK, WHITE }

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
    fun applyMove(x: Int, y: Int, color: Stone): Int {
        set(x, y, color)
        val opponent = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
        var captured = 0
        for ((nx, ny) in neighbors(x, y)) {
            if (grid[ny][nx] == opponent) {
                val group = groupAt(nx, ny)
                if (!groupHasLiberty(group)) {
                    group.forEach { (gx, gy) -> grid[gy][gx] = Stone.EMPTY }
                    captured += group.size
                }
            }
        }
        return captured
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
