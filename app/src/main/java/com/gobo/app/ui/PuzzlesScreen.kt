package com.gobo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gobo.app.net.PuzzleCollection
import com.gobo.app.net.formatRank

/**
 * Body content for the "Puzzles" destination: a browse list of puzzle (tsumego) collections. Tap a
 * collection to open its first puzzle and solve on the board. The top bar is owned by the drawer
 * scaffold. Pure REST GETs — no socket, no analytics.
 */
@Composable
fun PuzzlesScreen(vm: PuzzlesViewModel, onOpen: (PuzzleCollection) -> Unit) {
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Puzzle collections", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::load) { Text("Refresh") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                PuzzlesUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is PuzzlesUiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                is PuzzlesUiState.Ready -> if (s.collections.isEmpty()) {
                    Text(
                        "No puzzle collections found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // Empty collections (no playable starting puzzle) are noise; skip them.
                        items(
                            s.collections.filter { it.startingPuzzleId > 0 && it.puzzleCount > 0 },
                            key = { it.id },
                        ) { c ->
                            ListItem(
                                modifier = Modifier.clickable { onOpen(c) },
                                headlineContent = { Text(c.name) },
                                supportingContent = { Text(collectionSubtitle(c)) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** "12 puzzles · 19×19 · 30k–5d" — only the parts we actually know. */
private fun collectionSubtitle(c: PuzzleCollection): String = buildList {
    add(if (c.puzzleCount == 1) "1 puzzle" else "${c.puzzleCount} puzzles")
    add("${c.width}×${c.height}")
    if (c.maxRank > 0) add("${formatRank(c.minRank.toDouble())}–${formatRank(c.maxRank.toDouble())}")
}.joinToString(" · ")
