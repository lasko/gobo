package com.gobo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gobo.app.net.Bot
import com.gobo.app.net.ChallengeSpec
import com.gobo.app.net.Opponent

private val STANDARD_SIZES = listOf(9, 13, 19)
private val RULES = listOf("japanese", "chinese", "korean", "aga", "ing", "nz")
private val SYSTEMS = listOf("fischer", "byoyomi", "simple")
private val COLORS = listOf("automatic", "black", "white")

/** Default [p1, p2, p3] for a (system, speed) pair, in seconds (periods for byoyomi p3). */
private fun defaultParams(system: String, speed: String): Triple<Int, Int, Int> = when (system) {
    "fischer" -> when (speed) {
        "blitz" -> Triple(30, 10, 60)
        "correspondence" -> Triple(259200, 86400, 604800)
        else -> Triple(120, 30, 300)
    }
    "byoyomi" -> when (speed) {
        "blitz" -> Triple(30, 5, 5)
        "correspondence" -> Triple(604800, 86400, 5)
        else -> Triple(600, 30, 5)
    }
    else -> when (speed) { // simple: per_move only (p1)
        "blitz" -> Triple(10, 0, 0)
        "correspondence" -> Triple(86400, 0, 0)
        else -> Triple(60, 0, 0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGameScreen(
    vm: NewGameViewModel,
    onGameCreated: (gameId: Long, challengeId: Long) -> Unit,
) {
    val botsState by vm.bots.collectAsState()
    val submitState by vm.submit.collectAsState()

    // --- form state ---
    var vsComputer by remember { mutableStateOf(true) }
    var selectedBot by remember { mutableStateOf<Bot?>(null) }
    var boardSize by remember { mutableIntStateOf(9) }
    var ranked by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf("automatic") }
    var handicap by remember { mutableIntStateOf(0) }
    var komiAuto by remember { mutableStateOf(true) }
    var komi by remember { mutableStateOf("6.5") }
    var rules by remember { mutableStateOf("japanese") }
    var speed by remember { mutableStateOf("live") }
    var system by remember { mutableStateOf("fischer") }
    var name by remember { mutableStateOf("") }
    var disableAnalysis by remember { mutableStateOf(false) }
    var pauseWeekends by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(false) }

    val defaults = remember(system, speed) { defaultParams(system, speed) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var p3 by remember { mutableStateOf("") }
    // Reset time fields to sensible defaults whenever system/speed changes.
    LaunchedEffect(defaults) {
        p1 = defaults.first.toString()
        p2 = defaults.second.toString()
        p3 = defaults.third.toString()
    }

    // A bot may restrict board sizes / time systems — honor that when one is picked,
    // but only ever offer the three standard OGS sizes.
    val bot = selectedBot.takeIf { vsComputer }
    val sizeOptions = bot?.allowedSizes
        ?.let { allowed -> STANDARD_SIZES.filter { it in allowed } }
        ?.takeIf { it.isNotEmpty() } ?: STANDARD_SIZES
    val systemOptions = bot?.allowedSystems?.takeIf { it.isNotEmpty() } ?: SYSTEMS
    LaunchedEffect(sizeOptions) { if (boardSize !in sizeOptions) boardSize = sizeOptions.first() }
    LaunchedEffect(systemOptions) { if (system !in systemOptions) system = systemOptions.first() }

    // On a successful post, open the game screen: a bot game starts immediately (Playing), while an
    // open seek opens the waiting-for-opponent screen (challengeId drives the keepalive + Cancel).
    LaunchedEffect(submitState) {
        val s = submitState
        if (s is SubmitState.Success && s.result.gameId > 0) {
            vm.resetSubmit()
            onGameCreated(s.result.gameId, if (s.result.vsBot) 0L else s.result.challengeId)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Opponent
        SectionLabel("Opponent")
        ChoiceRow(
            options = listOf("Computer" to true, "Human" to false),
            selected = vsComputer,
            onSelect = { vsComputer = it },
        )

        if (vsComputer) {
            when (val b = botsState) {
                BotsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Finding online bots…", style = MaterialTheme.typography.bodySmall)
                }

                is BotsState.Error -> Column {
                    Text(b.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::loadBots) { Text("Retry") }
                }

                is BotsState.Ready -> BotDropdown(
                    bots = b.bots,
                    selected = selectedBot,
                    onSelect = { selectedBot = it },
                )
            }
        } else {
            Text(
                "Posts an open challenge any player can accept. It appears in " +
                    "My Games once someone joins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Board
        SectionLabel("Board size")
        ChoiceRow(
            options = sizeOptions.map { "${it}×$it" to it },
            selected = boardSize,
            onSelect = { boardSize = it },
        )

        // Rules + speed
        Dropdown("Rules", RULES, rules) { rules = it }
        SectionLabel("Speed")
        ChoiceRow(
            options = listOf("Blitz" to "blitz", "Live" to "live", "Correspondence" to "correspondence"),
            selected = speed,
            onSelect = { speed = it },
        )

        // Time control
        Dropdown("Time system", systemOptions, system) { system = it }
        TimeParamFields(system, p1, p2, p3, { p1 = it }, { p2 = it }, { p3 = it })

        HorizontalDivider()

        // Color + handicap
        SectionLabel("Your color")
        ChoiceRow(
            options = listOf("Auto" to "automatic", "Black ●" to "black", "White ○" to "white"),
            selected = color,
            onSelect = { color = it },
        )
        Stepper("Handicap", handicap, 0..9) { handicap = it }

        // Komi
        SwitchRow("Automatic komi", komiAuto) { komiAuto = it }
        if (!komiAuto) {
            OutlinedTextField(
                value = komi,
                onValueChange = { komi = it },
                label = { Text("Komi") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider()

        // Toggles
        SwitchRow("Ranked", ranked) { ranked = it }
        SwitchRow("Disable analysis", disableAnalysis) { disableAnalysis = it }
        SwitchRow("Pause on weekends", pauseWeekends) { pauseWeekends = it }
        SwitchRow("Private", isPrivate) { isPrivate = it }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Game name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Submit + result
        val submitting = submitState is SubmitState.Submitting
        val canSubmit = !submitting && !(vsComputer && selectedBot == null)
        Button(
            onClick = {
                vm.submit(
                    buildSpec(
                        vsComputer, selectedBot, name, boardSize, ranked, color, handicap,
                        komiAuto, komi, rules, speed, system, p1, p2, p3,
                        disableAnalysis, pauseWeekends, isPrivate,
                    )
                )
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (vsComputer) "Challenge bot" else "Post open challenge")
            }
        }

        when (val s = submitState) {
            is SubmitState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)

            // Success navigates into the game/waiting screen (handled by the LaunchedEffect above).
            else -> Unit
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun buildSpec(
    vsComputer: Boolean, bot: Bot?, name: String, boardSize: Int, ranked: Boolean,
    color: String, handicap: Int, komiAuto: Boolean, komi: String, rules: String,
    speed: String, system: String, p1: String, p2: String, p3: String,
    disableAnalysis: Boolean, pauseWeekends: Boolean, isPrivate: Boolean,
): ChallengeSpec {
    fun i(s: String) = s.toIntOrNull() ?: 0
    val params = when (system) {
        "fischer" -> mapOf("initial_time" to i(p1), "time_increment" to i(p2), "max_time" to i(p3))
        "byoyomi" -> mapOf("main_time" to i(p1), "period_time" to i(p2), "periods" to i(p3))
        else -> mapOf("per_move" to i(p1))
    }
    return ChallengeSpec(
        opponent = if (vsComputer && bot != null) Opponent.Computer(bot) else Opponent.Human,
        name = name,
        boardSize = boardSize,
        ranked = ranked,
        color = color,
        handicap = handicap,
        komiAuto = komiAuto,
        komi = komi.toDoubleOrNull() ?: 6.5,
        rules = rules,
        speed = speed,
        timeControl = system,
        timeParams = params,
        disableAnalysis = disableAnalysis,
        pauseOnWeekends = pauseWeekends,
        isPrivate = isPrivate,
    )
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.replaceFirstChar { it.uppercase() }) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotDropdown(bots: List<Bot>, selected: Bot?, onSelect: (Bot) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.username} (${it.rank})" } ?: "Select a bot",
            onValueChange = {},
            readOnly = true,
            label = { Text("Bot (${bots.size} online)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bots.forEach { b ->
                DropdownMenuItem(
                    text = { Text("${b.username} (${b.rank})") },
                    onClick = { onSelect(b); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun TimeParamFields(
    system: String, p1: String, p2: String, p3: String,
    onP1: (String) -> Unit, onP2: (String) -> Unit, onP3: (String) -> Unit,
) {
    val labels = when (system) {
        "fischer" -> listOf("Initial time (s)", "Increment (s)", "Max time (s)")
        "byoyomi" -> listOf("Main time (s)", "Period time (s)", "Periods")
        else -> listOf("Time per move (s)")
    }
    val values = listOf(p1 to onP1, p2 to onP2, p3 to onP3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { idx, label ->
            val (value, onChange) = values[idx]
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(it.filter(Char::isDigit)) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { if (value > range.first) onChange(value - 1) }) { Text("–") }
        Text("$value", Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = { if (value < range.last) onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
