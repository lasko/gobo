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

/** Body content for the "My Games" destination. The top bar is owned by the drawer scaffold. */
@Composable
fun GameListScreen(
    vm: GameListViewModel,
    onSelectGame: (Long) -> Unit,
    onNewGame: () -> Unit,
) {
    val state by vm.state.collectAsState()

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

            is GameListUiState.Ready -> if (s.games.isEmpty()) {
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
