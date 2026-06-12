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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** How often My Games silently refreshes while it's the visible, resumed screen. */
private const val GAMES_REFRESH_INTERVAL_MS = 10_000L

/** Body content for the "My Games" destination. The top bar is owned by the drawer scaffold. */
@Composable
fun GameListScreen(
    vm: GameListViewModel,
    onSelectGame: (Long) -> Unit,
    onNewGame: () -> Unit,
) {
    val state by vm.state.collectAsState()

    // Live-update while My Games is on screen: silently re-fetch on an interval so turn badges and
    // accepted challenges move without the manual Refresh button. repeatOnLifecycle keeps the loop
    // running only while RESUMED, so a backgrounded app stops polling (no wasted requests/battery).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(GAMES_REFRESH_INTERVAL_MS)
                vm.refresh()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            GameListUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            is GameListUiState.Error -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::load) { Text("Retry") }
            }

            is GameListUiState.Ready -> if (s.games.isEmpty() && s.pending.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No active games.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onNewGame) { Text("Start a new game") }
                }
            } else {
                LazyColumn {
                    // Pending (sent, not-yet-accepted) challenges first — they're the ones
                    // the player can still act on by cancelling.
                    if (s.pending.isNotEmpty()) {
                        item { SectionHeader("Pending challenges") }
                        items(s.pending, key = { "challenge-${it.id}" }) { challenge ->
                            ListItem(
                                headlineContent = {
                                    Text(challenge.name.ifBlank { "Open challenge" })
                                },
                                supportingContent = {
                                    Text(
                                        "${challenge.boardSize}×${challenge.boardSize} · " +
                                            "${if (challenge.ranked) "ranked" else "unranked"} · " +
                                            "waiting for an opponent",
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { vm.cancelChallenge(challenge.id) }) {
                                        Text("Cancel")
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                        // Only label the games section when a pending section sits above it.
                        if (s.games.isNotEmpty()) item { SectionHeader("Active games") }
                    }
                    items(s.games, key = { it.id }) { game ->
                        ListItem(
                            modifier = Modifier.clickable { onSelectGame(game.id) },
                            headlineContent = {
                                Text("${game.blackUsername} vs ${game.whiteUsername}")
                            },
                            supportingContent = {
                                Text("${game.boardSize}×${game.boardSize} · #${game.id}")
                            },
                            trailingContent = {
                                if (game.myTurn) Badge { Text("Your turn") }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
