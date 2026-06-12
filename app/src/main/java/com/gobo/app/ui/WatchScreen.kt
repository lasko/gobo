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
import com.gobo.app.net.parseGameId

/**
 * Body content for the "Watch Game" destination: a live-games list to browse (tap a game to
 * spectate it read-only), plus a "watch by link" field for a shared game number/URL ([parseGameId]).
 * The top bar is owned by the drawer scaffold.
 */
@Composable
fun WatchScreen(vm: LiveGamesViewModel, onWatch: (Long) -> Unit) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val pastedId = parseGameId(input)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live games", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::refresh) { Text("Refresh") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                LiveGamesUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is LiveGamesUiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                is LiveGamesUiState.Ready -> if (s.games.isEmpty()) {
                    Text(
                        "No live games right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.games, key = { it.id }) { g ->
                            ListItem(
                                modifier = Modifier.clickable { onWatch(g.id) },
                                headlineContent = { Text("${g.blackName} vs ${g.whiteName}") },
                                supportingContent = { Text("${g.width}×${g.height} · move ${g.moveNumber}") },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text(
            "Or watch by link",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("Game number or link") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { pastedId?.let(onWatch) }, enabled = pastedId != null) { Text("Watch") }
        }
    }
}
