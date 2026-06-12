package com.gobo.app.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Semantic colors for the flat goban. A `null` border means "no border in this
 * theme" — white stones only need an outline on the light background, black
 * stones only on the dark one. Provided by the app theme via [LocalBoardColors].
 */
data class BoardColors(
    val background: Color,
    val gridLine: Color,
    val label: Color,
    val whiteFill: Color,
    val whiteBorder: Color?,
    val blackFill: Color,
    val blackBorder: Color?,
    val lastMove: Color,
) {
    companion object {
        val Light = BoardColors(
            background = Color(0xFFF8F9FA),
            gridLine = Color(0xFFDEE2E6),
            label = Color(0xFF868E96),
            whiteFill = Color(0xFFFFFFFF),
            // A pure-white stone on the near-white board reads only by its outline,
            // so the border must sit clearly darker than the grid lines (gray-3).
            whiteBorder = Color(0xFF868E96),
            blackFill = Color(0xFF212529),
            blackBorder = null,
            lastMove = Color(0xFF20C997),
        )
        val Dark = BoardColors(
            background = Color(0xFF0F172A),
            gridLine = Color(0xFF334155),
            label = Color(0xFF64748B),
            whiteFill = Color(0xFFE2E8F0),
            whiteBorder = null,
            blackFill = Color(0xFF000000),
            blackBorder = Color(0xFF475569),
            lastMove = Color(0xFF06B6D4),
        )
    }
}

val LocalBoardColors = staticCompositionLocalOf { BoardColors.Light }

// Go columns are lettered left-to-right, skipping "I" to avoid confusing it with 1/l.
private const val COLUMN_LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"

// Fraction of the board reserved on each side for coordinate labels.
private const val MARGIN_FRACTION = 0.07f

/**
 * Draws a flat goban and reports tapped intersections. Pure rendering — no state
 * is kept here; the caller owns BoardState and decides what to do with taps.
 * [lastMove] (x, y), when set, is marked with the accent color.
 */
@Composable
fun GoBoard(
    state: BoardState,
    modifier: Modifier = Modifier,
    lastMove: Pair<Int, Int>? = null,
    ghostMove: Pair<Int, Int>? = null,
    ghostColor: Stone = Stone.EMPTY,
    invalidCell: Pair<Int, Int>? = null,
    onTap: (x: Int, y: Int) -> Unit,
) {
    val n = state.size
    val colors = LocalBoardColors.current
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(16.dp)
            .pointerInput(n) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    val margin = w * MARGIN_FRACTION
                    val cell = (w - 2 * margin) / n
                    val origin = margin + cell / 2f
                    val x = ((pos.x - origin) / cell).roundToInt().coerceIn(0, n - 1)
                    val y = ((pos.y - origin) / cell).roundToInt().coerceIn(0, n - 1)
                    onTap(x, y)
                }
            }
    ) {
        val w = size.width
        val margin = w * MARGIN_FRACTION
        val cell = (w - 2 * margin) / n
        val origin = margin + cell / 2f
        val gridEnd = origin + (n - 1) * cell
        val lineStroke = 1.5.dp.toPx()

        // Flat board: the surface matches the app background, so the board reads as
        // geometry rather than a wooden object.
        drawRect(colors.background)

        // Grid lines
        for (i in 0 until n) {
            val p = origin + i * cell
            drawLine(colors.gridLine, Offset(origin, p), Offset(gridEnd, p), lineStroke)
            drawLine(colors.gridLine, Offset(p, origin), Offset(p, gridEnd), lineStroke)
        }

        // Coordinate labels around all four sides (letters skip "I"; rows count up
        // from the bottom, matching OGS).
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = colors.label.toArgb()
            textSize = w * 0.034f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        fun baseline(centerY: Float) = centerY - (fm.ascent + fm.descent) / 2f
        val canvas = drawContext.canvas.nativeCanvas
        for (i in 0 until n) {
            val p = origin + i * cell
            val letter = COLUMN_LETTERS[i].toString()
            val rowNum = (n - i).toString()
            canvas.drawText(letter, p, baseline(margin / 2f), paint)       // top
            canvas.drawText(letter, p, baseline(w - margin / 2f), paint)   // bottom
            canvas.drawText(rowNum, margin / 2f, baseline(p), paint)       // left
            canvas.drawText(rowNum, w - margin / 2f, baseline(p), paint)   // right
        }

        // Star points (hoshi) for 9/13/19 — solid dots in the grid color, a touch
        // heavier than the lines so they register without dominating.
        val stars = when (n) {
            19 -> listOf(3, 9, 15)
            13 -> listOf(3, 6, 9)
            9 -> listOf(2, 4, 6)
            else -> emptyList()
        }
        for (sx in stars) for (sy in stars) {
            drawCircle(colors.gridLine, lineStroke * 1.8f, Offset(origin + sx * cell, origin + sy * cell))
        }

        // Stones. Radius leaves a small gap between neighbors; borders are applied
        // only in the theme where a stone would otherwise blend into the board.
        val r = cell * 0.45f
        val border = 1.dp.toPx()
        for (y in 0 until n) for (x in 0 until n) {
            val center = Offset(origin + x * cell, origin + y * cell)
            when (state.grid[y][x]) {
                Stone.BLACK -> {
                    drawCircle(colors.blackFill, r, center)
                    colors.blackBorder?.let { drawCircle(it, r, center, style = Stroke(border)) }
                }
                Stone.WHITE -> {
                    drawCircle(colors.whiteFill, r, center)
                    colors.whiteBorder?.let { drawCircle(it, r, center, style = Stroke(border)) }
                }
                Stone.EMPTY -> {}
            }
        }

        // Last-move indicator: an accent dot centered on the most recent stone.
        lastMove?.let { (lx, ly) ->
            if (lx in 0 until n && ly in 0 until n && state.grid[ly][lx] != Stone.EMPTY) {
                drawCircle(colors.lastMove, cell * 0.14f, Offset(origin + lx * cell, origin + ly * cell))
            }
        }

        // Pending (ghost) stone for two-tap placement: the committed fill at half opacity.
        ghostMove?.let { (gx, gy) ->
            if (gx in 0 until n && gy in 0 until n && state.grid[gy][gx] == Stone.EMPTY) {
                val center = Offset(origin + gx * cell, origin + gy * cell)
                val ghostBorder = if (ghostColor == Stone.WHITE) colors.whiteBorder else colors.blackBorder
                drawCircle(if (ghostColor == Stone.WHITE) colors.whiteFill else colors.blackFill, r, center, alpha = 0.5f)
                // Carry the committed-stone border into the preview so a white ghost
                // stays visible on the light board (where the fill alone is near-invisible).
                ghostBorder?.let { drawCircle(it, r, center, alpha = 0.5f, style = Stroke(border)) }
            }
        }

        // Invalid-move flash: a red ring on the rejected intersection.
        invalidCell?.let { (ix, iy) ->
            if (ix in 0 until n && iy in 0 until n) {
                drawCircle(
                    Color(0xFFE03131), r, Offset(origin + ix * cell, origin + iy * cell),
                    style = Stroke(border * 2f),
                )
            }
        }
    }
}
