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
import com.gobo.app.net.OgsRest
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.UiConfig
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
    Settings("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyApp(rest: OgsRest, config: UiConfig, settings: SettingsStore, onLogout: () -> Unit) {
    var gameId by remember { mutableStateOf<Long?>(null) }
    var destination by remember { mutableStateOf(Destination.Games) }

    // An open game takes over the whole screen (no drawer) with its own back nav.
    val gid = gameId
    if (gid != null) {
        val vm = remember(gid) { GameViewModel(rest, OgsSocket(), config.playerId) }
        val confirmMoves by settings.confirmMoves.collectAsState()
        val chatEnabled by settings.chatEnabled.collectAsState()
        // chatEnabled is read once when the game opens; toggling it mid-game takes
        // effect on the next game (the socket connect flag is set at start()).
        LaunchedEffect(gid) { vm.start(gid, chatEnabled) }
        ImmersiveMode()
        GameScreen(
            vm,
            confirmMoves = confirmMoves,
            chatEnabled = chatEnabled,
            myPlayerId = config.playerId,
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
                        onSelectGame = { gameId = it },
                        onNewGame = { destination = Destination.NewGame },
                    )
                    Destination.NewGame -> NewGameScreen(
                        newGameVm,
                        onGameCreated = { id -> gameId = id; gameListVm.load() },
                        onViewGames = { destination = Destination.Games; gameListVm.load() },
                    )
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
                    TextButton(onClick = onBack) { Text("← Games") }
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
            when (val p = phase) {
                // Don't show a board until the server confirms the game exists.
                GamePhase.Connecting -> CenteredInfo {
                    CircularProgressIndicator()
                    Text("Waiting for the game to start…")
                }
                GamePhase.Playing -> {
                    val opponent = if (myColor == Stone.BLACK) whiteName else blackName
                    GameBoardBody(
                        board, blackName, whiteName, myColor, lastMove, captures,
                        ghostMove = ghost, ghostColor = myColor ?: Stone.EMPTY, invalidCell = invalidCell,
                        statusLine = when {
                            myColor == null -> null
                            ghost != null -> "Tap again to confirm"
                            myTurn -> "Your turn"
                            else -> "$opponent's turn"
                        },
                        emphasizeStatus = false,
                        onTap = { x, y -> onBoardTap(x, y) },
                    ) {
                        val pending = ghost
                        if (confirmMoves && pending != null) {
                            Button(onClick = { commit(pending.first, pending.second) }) { Text("Confirm") }
                            OutlinedButton(onClick = { ghost = null }) { Text("Cancel") }
                        } else {
                            Button(onClick = { vm.tap(-1, -1) }) { Text("Pass") }
                            OutlinedButton(onClick = { vm.resign() }) { Text("Resign") }
                        }
                    }
                }
                GamePhase.Scoring -> GameBoardBody(
                    board, blackName, whiteName, myColor, lastMove, captures,
                    statusLine = "Both players passed. Accept the score, or resume play.",
                    emphasizeStatus = true,
                    onTap = { _, _ -> },
                ) {
                    Button(onClick = { vm.acceptScore() }) { Text("Accept") }
                    OutlinedButton(onClick = { vm.resumeGame() }) { Text("Resume") }
                }
                // Finished game keeps the final position; a game that never started has none.
                is GamePhase.Over -> if (p.showBoard) {
                    GameBoardBody(
                        board, blackName, whiteName, myColor, lastMove, captures,
                        statusLine = p.message,
                        emphasizeStatus = true,
                        onTap = { _, _ -> },
                    ) {
                        Button(onClick = onBack) { Text("Back to games") }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerTag("●", blackName, capturesByBlack)
            PlayerTag("○", whiteName, capturesByWhite, alignEnd = true)
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

/** A player's stone color, name, and running prisoner (capture) count. */
@Composable
private fun PlayerTag(glyph: String, name: String, captures: Int, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text("$glyph $name", style = MaterialTheme.typography.bodyMedium)
        Text(
            "$captures captured",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
