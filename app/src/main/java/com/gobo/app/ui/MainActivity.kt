package com.gobo.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.gobo.app.auth.AuthManager
import com.gobo.app.auth.TokenStore
import com.gobo.app.board.BoardState
import com.gobo.app.board.GoBoard
import com.gobo.app.board.MoveLegality
import com.gobo.app.board.Stone
import com.gobo.app.net.ChatMessage
import com.gobo.app.net.GameClock
import com.gobo.app.net.OgsRest
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.UiConfig
import com.gobo.app.net.formatClock
import com.gobo.app.net.readClock
import com.gobo.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private sealed interface Session {
    data object NotLoggedIn : Session
    data object LoadingConfig : Session
    data class Ready(val config: UiConfig) : Session
}

class MainActivity : ComponentActivity() {

    private lateinit var store: TokenStore
    private lateinit var auth: AuthManager
    private lateinit var rest: OgsRest
    private lateinit var settings: SettingsStore

    private val session = MutableStateFlow<Session>(Session.NotLoggedIn)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let { data ->
            lifecycleScope.launch {
                session.value = Session.LoadingConfig
                auth.handleRedirect(data)
                session.value = rest.fetchUiConfig()
                    .map { Session.Ready(it) }
                    .getOrElse { Session.NotLoggedIn }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        auth = AuthManager(this, store)
        rest = OgsRest(store)
        settings = SettingsStore(this)

        if (store.isLoggedIn) {
            session.value = Session.LoadingConfig
            lifecycleScope.launch {
                session.value = rest.fetchUiConfig()
                    .map { Session.Ready(it) }
                    .getOrElse { Session.NotLoggedIn }
            }
        }

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            GoboTheme(themeMode) {
                val s by session.collectAsState()

                when (val current = s) {
                    Session.NotLoggedIn -> LoginScreen(
                        onLogin = { loginLauncher.launch(auth.buildAuthRequestIntent()) }
                    )
                    Session.LoadingConfig -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    is Session.Ready -> ReadyApp(
                        rest = rest,
                        config = current.config,
                        settings = settings,
                        onLogout = {
                            store.clear()
                            session.value = Session.NotLoggedIn
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        auth.dispose()
    }
}

@Composable
private fun LoginScreen(onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gobo", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with your Online-Go account. The app only stores your " +
                "login token — no tracking, no analytics.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogin) { Text("Sign in with OGS") }
    }
}

private enum class Destination(val title: String) {
    Games("My Games"),
    NewGame("New Game"),
    Watch("Watch Game"),
    Settings("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyApp(rest: OgsRest, config: UiConfig, settings: SettingsStore, onLogout: () -> Unit) {
    var gameId by remember { mutableStateOf<Long?>(null) }
    // True when the open game was opened to watch (read-only) rather than to play.
    var spectating by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(Destination.Games) }

    // An open game takes over the whole screen (no drawer) with its own back nav.
    val gid = gameId
    if (gid != null) {
        val vm = remember(gid) { GameViewModel(rest, OgsSocket(), config.playerId) }
        val confirmMoves by settings.confirmMoves.collectAsState()
        val chatPref by settings.chatEnabled.collectAsState()
        // Spectating is read-only: no chat (the connect stays minimal). Otherwise chatEnabled is
        // read once when the game opens; toggling it mid-game takes effect on the next game.
        val chatEnabled = chatPref && !spectating
        LaunchedEffect(gid) { vm.start(gid, chatEnabled) }
        // remember()-created, so onCleared won't fire — close the socket on leaving so it doesn't
        // keep auto-reconnecting in the background.
        DisposableEffect(gid) { onDispose { vm.close() } }
        ImmersiveMode()
        GameScreen(
            vm,
            confirmMoves = confirmMoves,
            chatEnabled = chatEnabled,
            myPlayerId = config.playerId,
            spectating = spectating,
            onBack = { gameId = null },
            onLogout = onLogout,
        )
        return
    }

    val gameListVm = remember(config.playerId) { GameListViewModel(rest, config) }
    val newGameVm = remember(config.playerId) { NewGameViewModel(rest, config) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Text("Gobo", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "@${config.username}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Destination.entries.forEach { dest ->
                    NavigationDrawerItem(
                        label = { Text(dest.title) },
                        selected = destination == dest,
                        onClick = {
                            destination = dest
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Log out") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                Spacer(Modifier.height(8.dp))
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(destination.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        if (destination == Destination.Games) {
                            TextButton(onClick = gameListVm::load) { Text("Refresh") }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (destination) {
                    Destination.Games -> GameListScreen(
                        gameListVm,
                        onSelectGame = { gameId = it; spectating = false },
                        onNewGame = { destination = Destination.NewGame },
                    )
                    Destination.NewGame -> NewGameScreen(
                        newGameVm,
                        onGameCreated = { id -> gameId = id; spectating = false; gameListVm.load() },
                        onViewGames = { destination = Destination.Games; gameListVm.load() },
                    )
                    Destination.Watch -> {
                        // Scoped to the Watch screen: opens its own query socket on entry, closed
                        // on leave (remember(), so onCleared won't fire). Re-created (fresh list)
                        // each time you return.
                        val liveVm = remember { LiveGamesViewModel(OgsSocket(), config.userJwt) }
                        DisposableEffect(Unit) { onDispose { liveVm.close() } }
                        WatchScreen(liveVm, onWatch = { id -> gameId = id; spectating = true })
                    }
                    Destination.Settings -> {
                        val themeMode by settings.themeMode.collectAsState()
                        val confirmMoves by settings.confirmMoves.collectAsState()
                        val chatEnabled by settings.chatEnabled.collectAsState()
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeChange = settings::setThemeMode,
                            confirmMoves = confirmMoves,
                            onConfirmMovesChange = settings::setConfirmMoves,
                            chatEnabled = chatEnabled,
                            onChatEnabledChange = settings::setChatEnabled,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
    vm: GameViewModel,
    confirmMoves: Boolean,
    chatEnabled: Boolean,
    myPlayerId: Int,
    spectating: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val board by vm.board.collectAsState()
    val phase by vm.phase.collectAsState()
    val (blackName, whiteName) = vm.playerNames.collectAsState().value
    val myColor by vm.myColor.collectAsState()
    val myTurn by vm.myTurn.collectAsState()
    val lastMove by vm.lastMove.collectAsState()
    val captures by vm.captures.collectAsState()
    val chat by vm.chat.collectAsState()
    val clock by vm.clock.collectAsState()
    val undoEnabled by vm.undoEnabled.collectAsState()
    val undoRequested by vm.undoRequested.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    val review by vm.review.collectAsState()
    val toMove by vm.toMove.collectAsState()

    // Local 1 Hz tick driving the live countdown: OGS only sends a fresh clock per move, so we
    // recompute remaining time off the anchor ourselves. Runs only while playing — a finished or
    // scoring game freezes the clock at its last value.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val ticking = phase == GamePhase.Playing
    LaunchedEffect(ticking) {
        while (ticking) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val view = LocalView.current
    var ghost by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var invalidCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Chat is read-only and opt-in; the sheet is reachable only when enabled. Unread =
    // messages arrived since the sheet was last open.
    var showChat by remember { mutableStateOf(false) }
    var seenCount by remember { mutableStateOf(0) }
    val unreadChat = (chat.size - seenCount).coerceAtLeast(0)
    LaunchedEffect(showChat, chat.size) { if (showChat) seenCount = chat.size }

    // Drop any pending preview when it's no longer our move.
    LaunchedEffect(myTurn, phase) { if (!myTurn || phase != GamePhase.Playing) ghost = null }
    // The invalid-move flash is momentary.
    LaunchedEffect(invalidCell) { if (invalidCell != null) { delay(350); invalidCell = null } }
    // A heavier buzz whenever the prisoner count climbs (either side capturing).
    val captureTotal = captures.first + captures.second
    var prevCaptureTotal by remember { mutableStateOf(captureTotal) }
    LaunchedEffect(captureTotal) {
        if (captureTotal > prevCaptureTotal) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        prevCaptureTotal = captureTotal
    }

    fun commit(x: Int, y: Int) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        vm.tap(x, y)
        ghost = null
    }

    fun onBoardTap(x: Int, y: Int) {
        if (myColor == null || !myTurn) return
        // Flash any locally-detectable illegal move (occupied, suicide, simple ko)
        // instead of sending it; OGS stays authoritative for everything else.
        if (vm.legalityOf(x, y) != MoveLegality.LEGAL) {
            ghost = null
            invalidCell = x to y
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        when {
            !confirmMoves -> commit(x, y)
            ghost == x to y -> commit(x, y)           // second tap confirms
            else -> ghost = x to y                     // first tap previews / moves ghost
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (blackName.isNotEmpty()) "$blackName vs $whiteName"
                        else "Game"
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(if (spectating) "← Back" else "← Games") }
                },
                actions = {
                    if (chatEnabled) {
                        IconButton(onClick = { showChat = true }) {
                            val glyph = @Composable {
                                Text("💬", style = MaterialTheme.typography.titleMedium)
                            }
                            if (unreadChat > 0) BadgedBox(badge = { Badge() }) { glyph() } else glyph()
                        }
                    }
                    TextButton(onClick = onLogout) { Text("Log out") }
                },
            )
        }
    ) { padding ->
        if (showChat) {
            ChatSheet(
                messages = chat,
                myPlayerId = myPlayerId,
                onSend = { vm.sendChat(it) },
                onDismiss = { showChat = false },
            )
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            // A dropped connection freezes the board; surface it so it doesn't read as a hung game.
            // The board stays put and resyncs from the snapshot the reconnect's game/connect triggers.
            if (reconnecting && phase != GamePhase.Connecting) {
                ReconnectingBanner(Modifier.align(Alignment.TopCenter))
            }
            when (val p = phase) {
                // Don't show a board until the server confirms the game exists.
                GamePhase.Connecting -> CenteredInfo {
                    CircularProgressIndicator()
                    Text("Waiting for the game to start…")
                }
                GamePhase.Playing -> {
                    val opponent = if (myColor == Stone.BLACK) whiteName else blackName
                    // A spectator has no side: read-only board, no action row, "whose move" status.
                    val toMoveName = if (toMove == Stone.BLACK) blackName else whiteName
                    GameBoardBody(
                        board, blackName, whiteName, myColor, lastMove, captures, clock, nowMs,
                        ghostMove = if (spectating) null else ghost,
                        ghostColor = myColor ?: Stone.EMPTY, invalidCell = invalidCell,
                        statusLine = when {
                            spectating -> "Spectating · $toMoveName to move"
                            myColor == null -> null
                            undoRequested -> "Undo requested — waiting for $opponent…"
                            ghost != null -> "Tap again to confirm"
                            myTurn -> "Your turn"
                            else -> "$opponent's turn"
                        },
                        emphasizeStatus = false,
                        onTap = { x, y -> if (!spectating) onBoardTap(x, y) },
                    ) {
                        val pending = ghost
                        when {
                            spectating -> {} // read-only: no actions
                            confirmMoves && pending != null -> {
                                Button(onClick = { commit(pending.first, pending.second) }) { Text("Confirm") }
                                OutlinedButton(onClick = { ghost = null }) { Text("Cancel") }
                            }
                            else -> {
                                // Undo takes back your own last move (bots accept); disabled once the
                                // opponent has replied or a request is already pending.
                                OutlinedButton(onClick = { vm.requestUndo() }, enabled = undoEnabled) {
                                    Text("Undo")
                                }
                                Button(onClick = { vm.tap(-1, -1) }) { Text("Pass") }
                                OutlinedButton(onClick = { vm.resign() }) { Text("Resign") }
                            }
                        }
                    }
                }
                GamePhase.Scoring -> GameBoardBody(
                    board, blackName, whiteName, myColor, lastMove, captures, clock, nowMs,
                    statusLine = "Both players passed. Accept the score, or resume play.",
                    emphasizeStatus = true,
                    onTap = { _, _ -> },
                ) {
                    Button(onClick = { vm.acceptScore() }) { Text("Accept") }
                    OutlinedButton(onClick = { vm.resumeGame() }) { Text("Resume") }
                }
                // Finished game keeps the final position and is reviewable move-by-move; a game
                // that never started has no board. The top-bar "← Games" is the back affordance,
                // so the action row is free for review navigation.
                is GamePhase.Over -> if (p.showBoard) {
                    val rev = review
                    GameBoardBody(
                        rev?.board ?: board, blackName, whiteName, myColor,
                        rev?.lastMove ?: lastMove, rev?.captures ?: captures, clock, nowMs,
                        statusLine = if (rev != null) "${p.message}  ·  move ${rev.index} / ${rev.total}"
                            else p.message,
                        emphasizeStatus = true,
                        onTap = { _, _ -> },
                    ) {
                        if (rev != null) {
                            OutlinedButton(onClick = { vm.reviewJump(0) }, enabled = rev.index > 0) { Text("⏮") }
                            OutlinedButton(onClick = { vm.reviewStep(-1) }, enabled = rev.index > 0) { Text("◀") }
                            OutlinedButton(onClick = { vm.reviewStep(1) }, enabled = rev.index < rev.total) { Text("▶") }
                            OutlinedButton(onClick = { vm.reviewJump(rev.total) }, enabled = rev.index < rev.total) { Text("⏭") }
                        } else {
                            Button(onClick = onBack) { Text("Back to games") }
                        }
                    }
                } else CenteredInfo {
                    Text(
                        p.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onBack) { Text("Back to games") }
                }
            }
        }
    }
}

/** Board view shared by the playing, scoring, and finished-with-board states. */
@Composable
private fun GameBoardBody(
    board: BoardState,
    blackName: String,
    whiteName: String,
    myColor: Stone?,
    lastMove: Pair<Int, Int>?,
    captures: Pair<Int, Int>,
    clock: GameClock?,
    nowMs: Long,
    ghostMove: Pair<Int, Int>? = null,
    ghostColor: Stone = Stone.EMPTY,
    invalidCell: Pair<Int, Int>? = null,
    statusLine: String?,
    emphasizeStatus: Boolean,
    onTap: (Int, Int) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val (capturesByBlack, capturesByWhite) = captures
        // Only the side to move ticks; readClock freezes the other. nowMs is shifted by the
        // server skew so the countdown tracks the server's clock, not just the device's.
        val serverNow = nowMs + (clock?.skewMs ?: 0L)
        val blackClock = clock?.let {
            formatClock(readClock(it.black, it.currentPlayerId == it.blackPlayerId, it.lastMoveMs, serverNow))
        }
        val whiteClock = clock?.let {
            formatClock(readClock(it.white, it.currentPlayerId == it.whitePlayerId, it.lastMoveMs, serverNow))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerTag("●", blackName, capturesByBlack, blackClock, clock?.currentPlayerId == clock?.blackPlayerId)
            PlayerTag("○", whiteName, capturesByWhite, whiteClock, clock?.currentPlayerId == clock?.whitePlayerId, alignEnd = true)
        }
        val colorLabel = when (myColor) {
            Stone.BLACK -> "You are playing Black ●"
            Stone.WHITE -> "You are playing White ○"
            else -> null
        }
        if (colorLabel != null) {
            Text(
                colorLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (statusLine != null) {
            Text(
                statusLine,
                style = if (emphasizeStatus) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodySmall,
                color = if (emphasizeStatus) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        GoBoard(
            state = board,
            lastMove = lastMove,
            ghostMove = ghostMove,
            ghostColor = ghostColor,
            invalidCell = invalidCell,
            onTap = onTap,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/**
 * A player's stone color, name, running prisoner (capture) count, and clock. The clock uses a
 * monospaced family with tabular figures so the countdown doesn't jitter between ticks, and is
 * tinted with the accent while it's this player's turn. [clockText] is null when no clock is known.
 */
@Composable
private fun PlayerTag(
    glyph: String,
    name: String,
    captures: Int,
    clockText: String?,
    isTurn: Boolean,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text("$glyph $name", style = MaterialTheme.typography.bodyMedium)
        if (clockText != null) {
            Text(
                clockText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                color = if (isTurn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "$captures captured",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Transient pill shown while the realtime socket is dropped and retrying. */
@Composable
private fun ReconnectingBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Reconnecting…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * In-game chat in a bottom sheet: a scrolling history plus a compose box. Only reachable
 * when chat is opted in. Your own line appears once the server echoes it back, so there's
 * no optimistic insert to de-duplicate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheet(
    messages: List<ChatMessage>,
    myPlayerId: Int,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Keep the newest message in view as lines arrive while the sheet is open.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    var draft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            Text("Chat", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (messages.isEmpty()) {
                Text(
                    "No messages yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages) { msg -> ChatLine(msg, isMine = msg.playerId == myPlayerId) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    singleLine = true,
                )
                Button(
                    onClick = { onSend(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatLine(msg: ChatMessage, isMine: Boolean) {
    Column {
        Text(
            msg.username.ifBlank { if (isMine) "You" else "Player" },
            style = MaterialTheme.typography.labelMedium,
            color = if (isMine) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(msg.body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Hides the status & nav bars while composed (gameplay), restoring them on exit. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A full-screen centered column for the connecting / never-started states. */
@Composable
private fun CenteredInfo(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
