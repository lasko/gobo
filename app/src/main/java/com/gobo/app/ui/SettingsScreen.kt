package com.gobo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.gobo.app.settings.ThemeMode

// The developer's tip jar. Opened in the user's browser on tap — no SDK, no tracking.
private const val KOFI_URL = "https://ko-fi.com/eigowaffles"

/** Body content for the "Settings" destination. The top bar is owned by the drawer scaffold. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    confirmMoves: Boolean,
    onConfirmMovesChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onThemeChange(mode) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = themeMode == mode, onClick = { onThemeChange(mode) })
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Gameplay", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onConfirmMovesChange(!confirmMoves) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Confirm moves")
                Text(
                    "Place a stone with a preview tap, then tap again to commit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = confirmMoves, onCheckedChange = onConfirmMovesChange)
        }

        // Push support to the bottom so it stays out of the way.
        Spacer(Modifier.weight(1f))
        SupportSection()
    }
}

@Composable
private fun SupportSection() {
    val uriHandler = LocalUriHandler.current
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(
        "Gobo is free, open, and tracker-free.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = { uriHandler.openUri(KOFI_URL) },
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        Text("Support development on Ko-fi ☕")
    }
}
