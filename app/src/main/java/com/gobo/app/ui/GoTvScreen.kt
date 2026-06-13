package com.gobo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.gobo.app.net.GoStream
import com.gobo.app.net.streamUrl

/**
 * Body content for the "GoTV" destination: a list of external live Go streams (Twitch/YouTube).
 * These aren't OGS games — tapping one **opens it in the device browser** (privacy hand-off, like the
 * OAuth Custom Tab; no embedded player). We render OGS' own text only and never load Twitch's CDN
 * images. The top bar is owned by the drawer scaffold.
 */
@Composable
fun GoTvScreen(vm: GoTvViewModel) {
    val state by vm.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Live Go streams", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Opens in your browser",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::load) { Text("Refresh") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                GoTvUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is GoTvUiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                is GoTvUiState.Ready -> {
                    // Only streams we can actually link to (a known source) are tappable/shown.
                    val streams = s.streams.mapNotNull { stream ->
                        streamUrl(stream)?.let { stream to it }
                    }
                    if (streams.isEmpty()) {
                        Text(
                            "No live streams right now.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(streams, key = { it.first.streamId }) { (stream, url) ->
                                ListItem(
                                    modifier = Modifier.clickable { uriHandler.openUri(url) },
                                    headlineContent = { Text(stream.title) },
                                    supportingContent = { Text(streamSubtitle(stream)) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "MidnightTheBlue · 21 watching · Twitch · EN" — only the parts we know. */
private fun streamSubtitle(s: GoStream): String = buildList {
    add(s.username)
    add(if (s.viewerCount == 1) "1 watching" else "${s.viewerCount} watching")
    s.source.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }?.let { add(it) }
    s.language.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
}.joinToString(" · ")
