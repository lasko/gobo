package com.gobo.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gobo.app.board.GoBoard
import com.gobo.app.board.Stone
import kotlinx.coroutines.delay

/**
 * Solve a single puzzle on the board. Reuses [GoBoard] for input and the invalid-cell flash; the
 * opponent's replies are auto-played by the ViewModel from the solution tree. Prev/next step through
 * the collection (via [onNavigate]); the top-bar back returns to the collection list ([onBack]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuzzleScreen(vm: PuzzleViewModel, onBack: () -> Unit, onNavigate: (Long) -> Unit) {
    val state by vm.state.collectAsState()
    val board by vm.board.collectAsState()
    val status by vm.status.collectAsState()
    val lastMove by vm.lastMove.collectAsState()
    val hint by vm.hint.collectAsState()
    val prevId by vm.prevId.collectAsState()
    val nextId by vm.nextId.collectAsState()

    val view = LocalView.current
    var invalidCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(invalidCell) { if (invalidCell != null) { delay(350); invalidCell = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((state as? PuzzleSolveUiState.Ready)?.puzzle?.name ?: "Puzzle")
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Puzzles") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                PuzzleSolveUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is PuzzleSolveUiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                is PuzzleSolveUiState.Ready -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val playingBlack = s.puzzle.playerColor == Stone.BLACK
                    val statusText = when (status) {
                        PuzzleStatus.Solving ->
                            if (playingBlack) "Your move — play Black ●" else "Your move — play White ○"
                        PuzzleStatus.Solved -> "Solved! ✓"
                        PuzzleStatus.Failed -> "Not quite — try again"
                    }
                    val statusColor = when (status) {
                        PuzzleStatus.Solved -> MaterialTheme.colorScheme.primary
                        PuzzleStatus.Failed -> MaterialTheme.colorScheme.error
                        PuzzleStatus.Solving -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    // The status, (collapsible) description, and board live in a scrolling region that
                    // takes the space left above the pinned controls — so a long teaching description
                    // can never push the buttons off-screen (the reported bug), while the action row
                    // and tap hint stay anchored at the bottom and always reachable.
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    ) {
                        Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor)
                        if (s.puzzle.description.isNotBlank()) {
                            PuzzleDescription(s.puzzle.id, s.puzzle.description)
                        }
                        Spacer(Modifier.height(8.dp))
                        GoBoard(
                            state = board,
                            lastMove = lastMove,
                            invalidCell = invalidCell,
                            hintCells = hint,
                            onTap = { x, y ->
                                when (vm.tap(x, y)) {
                                    PuzzleTap.ACCEPTED ->
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    PuzzleTap.OFF_TREE -> {
                                        invalidCell = x to y
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    }
                                    PuzzleTap.IGNORED -> {}
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { prevId?.let(onNavigate) },
                            enabled = prevId != null,
                        ) { Text("◀") }
                        // Retry restores the starting position; useful after a wrong move or to redo.
                        OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text("Retry") }
                        // Hint highlights the move(s) toward the solution; toggles, and only while solving.
                        val hintOn = hint.isNotEmpty()
                        val hintLabel = @Composable { Text("💡 Hint") }
                        if (hintOn) {
                            FilledTonalButton(
                                onClick = vm::toggleHint,
                                modifier = Modifier.weight(1f),
                                content = { hintLabel() },
                            )
                        } else {
                            OutlinedButton(
                                onClick = vm::toggleHint,
                                enabled = status == PuzzleStatus.Solving,
                                modifier = Modifier.weight(1f),
                                content = { hintLabel() },
                            )
                        }
                        OutlinedButton(
                            onClick = { nextId?.let(onNavigate) },
                            enabled = nextId != null,
                        ) { Text("▶") }
                    }
                    Text(
                        "Tap to place a stone. The opponent replies automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The puzzle's description, collapsed to a few lines by default with a Show more/less toggle so a
 * long teaching write-up doesn't crowd out the board. [puzzleId] keys the expanded state so it
 * resets when navigating to another puzzle. The toggle only appears when the text actually overflows
 * (detected via the text layout), so short descriptions show in full with no clutter.
 */
@Composable
private fun PuzzleDescription(puzzleId: Long, description: String) {
    var expanded by remember(puzzleId) { mutableStateOf(false) }
    var overflows by remember(puzzleId) { mutableStateOf(false) }
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DESCRIPTION_LINES,
        overflow = TextOverflow.Ellipsis,
        // While collapsed, note whether the text was clipped so we only offer the toggle when needed.
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        modifier = Modifier.padding(top = 2.dp),
    )
    if (overflows || expanded) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            Text(if (expanded) "Show less" else "Show more")
        }
    }
}

private const val COLLAPSED_DESCRIPTION_LINES = 3
