package com.gobo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gobo.app.net.PuzzleCollection
import com.gobo.app.net.PuzzleSort
import com.gobo.app.net.formatCount
import com.gobo.app.net.formatDifficulty
import com.gobo.app.net.formatPuzzleDate

/**
 * Body content for the "Puzzles" destination: a browse list of puzzle (tsumego) collections with
 * the same fields the OGS web browser shows (difficulty, count, rating, views, solved, created) and
 * **server-side sorting** by any of them so people can find puzzles worth doing. Tap a collection to
 * open its first puzzle. The top bar is owned by the drawer scaffold. REST GETs only — no analytics.
 */
@Composable
fun PuzzlesScreen(vm: PuzzlesViewModel, onOpen: (PuzzleCollection) -> Unit) {
    val state by vm.state.collectAsState()
    val sort by vm.sort.collectAsState()

    Column(Modifier.fillMaxSize()) {
        SortBar(active = sort, onSort = vm::sortBy)
        HorizontalDivider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                PuzzlesUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is PuzzlesUiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                is PuzzlesUiState.Ready -> {
                    // Empty collections (no playable starting puzzle) are noise; skip them.
                    val collections = s.collections.filter { it.startingPuzzleId > 0 && it.puzzleCount > 0 }
                    if (collections.isEmpty()) {
                        Text(
                            "No puzzle collections found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(collections, key = { it.id }) { c ->
                                CollectionRow(c, onClick = { onOpen(c) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Horizontally-scrolling chips to pick the sort field; the active one shows its direction (▼/▲). */
@Composable
private fun SortBar(active: PuzzleSortState, onSort: (PuzzleSort) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sort:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PuzzleSort.entries.forEach { field ->
            val selected = active.field == field
            FilterChip(
                selected = selected,
                onClick = { onSort(field) },
                label = {
                    Text(if (selected) "${field.label} ${if (active.descending) "▼" else "▲"}" else field.label)
                },
            )
        }
    }
}

/** One collection: name, owner, and the metric line (difficulty · count · rating · views · solved · date). */
@Composable
private fun CollectionRow(c: PuzzleCollection, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            metricLine(c),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        val by = buildList {
            if (c.ownerName.isNotBlank()) add("by ${c.ownerName}")
            formatPuzzleDate(c.created).takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        if (by.isNotBlank()) {
            Text(
                by,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** "30k–5d · 352 puzzles · ★4.5 (698) · 48.1K views · 23.4K solved" — only the parts we know. */
private fun metricLine(c: PuzzleCollection): String = buildList {
    add(formatDifficulty(c.minRank, c.maxRank))
    add(if (c.puzzleCount == 1) "1 puzzle" else "${c.puzzleCount} puzzles")
    if (c.ratingCount > 0) add("★%.1f (%s)".format(java.util.Locale.ROOT, c.rating, formatCount(c.ratingCount)))
    if (c.viewCount > 0) add("${formatCount(c.viewCount)} views")
    if (c.solvedCount > 0) add("${formatCount(c.solvedCount)} solved")
}.joinToString(" · ")
