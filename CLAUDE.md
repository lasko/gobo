# Gobo

A **privacy-first Android client for [online-go.com](https://online-go.com) (OGS)** — play Go without trackers. Jetpack Compose UI, OAuth2 (PKCE) login, REST + WebSocket against the OGS public API.

The privacy stance is the product, not a nice-to-have. Keep it intact (see [Non-negotiable constraints](#non-negotiable-constraints)).

## Tech stack

- **Language:** Kotlin 2.0.21, JVM target 17
- **UI:** Jetpack Compose (BOM `2024.09.00`, Material3), single-Activity
- **Async:** Coroutines + `StateFlow`
- **Net:** OkHttp 4.12 (REST + WebSocket), kotlinx.serialization JSON 1.7.3
- **Auth:** AppAuth 0.11.1 (OAuth2 + PKCE via Custom Tab), `androidx.security.crypto` EncryptedSharedPreferences for token at rest
- **Build:** AGP 8.13.2, Gradle 8.14.4 wrapper. `minSdk 26`, `targetSdk 34`, `compileSdk 36`

## Package layout

```
app/src/main/java/com/gobo/app/
  auth/      AuthManager (PKCE flow), TokenStore (encrypted token storage)
  board/     BoardState (capture + move legality/ko), MoveReplay (pure snapshot replay), GoBoard (flat Compose canvas) + BoardColors/LocalBoardColors, Stone enum, OgsCoord
  net/       Ogs (all endpoints), OgsRest (REST), OgsParse (pure REST response parsers), OgsSocket (realtime), Challenges (models, active-bots parser, buildChallengeBody)
  settings/  SettingsStore (plain prefs: theme + confirmMoves + chatEnabled), ThemeMode enum
  ui/        MainActivity (Session + drawer nav + ImmersiveMode), Theme (GoboTheme schemes), Type (Poppins typography), *Screen (Compose), *ViewModel (StateFlow)
  res/       font/ (bundled Poppins TTFs), drawable+mipmap-anydpi-v26 (adaptive launcher icon), values/colors.xml
```

## Design system

Flat, geometry-first, Material3. **No hardcoded hex in components** — read `MaterialTheme.colorScheme` or `LocalBoardColors`.
- **Color:** hand-tuned light/dark schemes in `ui/Theme.kt` (no Material You dynamic color). Accent is mint `#20C997` (light) / cyan `#06B6D4` (dark). The goban's own palette (background, grid, stone fills/borders, last-move) lives in `board/BoardColors` and is provided through `LocalBoardColors` per theme — the board surface matches the app background (no wood skin).
- **Type:** Poppins, **bundled locally** in `res/font` (OFL, license in `assets/`). Do **not** switch to the `GoogleFont`/downloadable-fonts API — it fetches from Google's servers (violates the privacy charter).
- **Board:** stones are 45% of cell; white gets a border only in light, black only in dark; last move marked with the accent. `GoBoard` also renders a translucent `ghostMove` (two-tap preview) and a red `invalidCell` flash.

## Common commands

> **Building from the CLI on this machine requires `JAVA_HOME`** — it is not on PATH. Point it at the Android Studio JBR. Inside Android Studio this is automatic.

```powershell
# PowerShell — set once per shell session
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat compileDebugKotlin   # fast code + Compose check (preferred inner-loop)
.\gradlew.bat assembleDebug        # full debug APK
.\gradlew.bat installDebug         # build + install on connected device/emulator
.\gradlew.bat lint                 # Android Lint -> app/build/reports/lint-results-debug.html
.\gradlew.bat testDebugUnitTest    # JVM unit tests (pure-logic suite — see Testing)
.\gradlew.bat --stop               # kill stuck Gradle daemons
```

`compileDebugKotlin` runs the Compose compiler plugin, so most errors (type, exhaustiveness, composable misuse) surface there without a full `assembleDebug`.

### Debugging a running app

```powershell
adb logcat --pid (adb shell pidof -s com.gobo.app)   # app logs only
adb shell am start -n com.gobo.app/.ui.MainActivity   # launch
```

OGS API responses are easy to inspect with the public REST API (no auth) or by probing the socket — useful for confirming payload shapes before coding against them. Example: `https://online-go.com/api/v1/players/?username=<name>`.

## Architecture notes

- **Single Activity, Compose-only.** `MainActivity` holds a `Session` sealed interface (`NotLoggedIn` → `LoadingConfig` → `Ready`). When `Ready`, `ReadyApp` renders a `ModalNavigationDrawer` (My Games / New Game / Log out). An open game takes over the full screen with its own back nav.
- **ViewModel + StateFlow.** Each screen has a ViewModel exposing a private `MutableStateFlow` and a public `asStateFlow()`. UI state is a sealed interface (`Loading` / `Ready(data)` / `Error(msg)`), plus a `Submitting`/`Success`/`Idle` pattern for actions. Match this when adding screens.
- **Server is authoritative.** `BoardState` applies the OGS `board[][]` snapshot directly; it also has local flood-fill capture logic so incremental `game/move` events render captures without a round-trip. Don't reimplement game rules client-side beyond this responsiveness shim.
- **REST vs realtime split.** `OgsRest` = request/response (config, overview, challenges). `OgsSocket` = realtime game play + broadcasts. The socket speaks raw JSON arrays: client `["command", data, id?]`, server `["event_name", data]`.

## OGS integration cheat-sheet

- **All endpoints live in `net/Ogs.kt`** — add new ones there, never inline a URL, so the network surface stays auditable.
- **Coordinates:** OGS moves are two letters, column-then-row `a`..`s` (0-indexed); `".."` = pass. Encode/decode via `board/OgsCoord`.
- **Online bots:** the socket broadcasts an `active-bots` event on connect (no auth needed) — a map keyed by bot id with `username`, `ranking`, and a `config` block (`allowed_board_sizes`, `allowed_time_control_systems`, `allow_ranked`, `decline_new_challenges`, …). Parsed by `parseActiveBots` in `net/Challenges.kt`. This is how the New Game screen lists only live, accepting bots.
- **Challenges:** `POST /api/v1/challenges/` = open seek (any human); `POST /api/v1/players/{id}/challenge/` = direct challenge. Body shape and time-control parameter maps (fischer/byoyomi/simple) are built in `OgsRest.buildChallengeBody`. Open challenges use a wide `min/max_ranking` (OGS clamps server-side) — first knob to revisit if a human challenge is rejected. The POST returns `{"status":"ok","challenge":<id>,"game":<id>}` **optimistically** — a `game` id even when the opponent hasn't accepted yet.
- **Pending sent challenges:** an open seek nobody has accepted is invisible in the overview (it isn't a game yet), so list them with `GET /api/v1/me/challenges` (returns both directions — `parseSentChallenges` filters to ones *you* sent) and withdraw with `DELETE /api/v1/me/challenges/{id}`. My Games shows them as a cancellable **Pending** section. (Endpoint shapes inferred from the documented `POST …/me/challenges/{id}/accept`; verify the list/delete responses against a live account.)
- **Bot challenges can still be declined** after that "ok": the bot sends a `notification` socket event with `type:"gameOfferRejected"` (with `game_id`, a human-readable `message`, and `rejection_details.rejection_code`, e.g. `too_many_games` — most `*-humanlike-kata` bots allow only **1** concurrent game). `GameViewModel` listens for this so a declined challenge surfaces the reason instead of stranding the user on a never-loading board.
- **Playing a game (realtime):** send `game/connect` with `{game_id, player_id, chat}` — **`player_id` is required**; without it OGS accepts the connect but never pushes the `game/<id>/gamedata` snapshot, leaving a blank default board. The snapshot (board, players, clock, `phase`) is the signal the game truly exists; `GameViewModel` stays in a "connecting" state until it arrives. A finished game re-sends `gamedata` with `phase:"finished"` plus `outcome`/`winner`; resign/timeout may instead come as a `game/<id>/phase` event (`GameViewModel` handles both, falling back to `GET /api/v1/games/{id}` for the result). `GamePhase` (in `GameViewModel`) models the screen lifecycle: `Connecting → Playing → Scoring → Over`.
- **In-game chat (opt-in):** off by default to keep the connect minimal — `game/connect` sends `chat:false` and no chat flows. The `SettingsStore.chatEnabled` toggle flips `game/connect` to `chat:true`, after which the server pushes `game/<id>/chat` events shaped `{channel, line:{username, player_id, date, chat_id, body}}` (older payloads wrap the line under `message`; `parseGameChat` accepts either). The `line.body` is one of three shapes: a **plain string** (human chat); a **`{"type":"translated","en":…,<lang>:…}` object** — bots send their greeting and end-of-game notes this way, and we render the `en` text; or an **`analysis`/`review` variation object**, skipped because the read-only view shows text only. `chatBodyText` in `net/GameChat.kt` does this discrimination (**don't** treat every non-string body as skippable — that drops all bot chat, the bug fixed here). `GameViewModel` collects the lines into `chat`. **OGS re-sends the whole chat log on some lifecycle events (notably at game end, prefixed by a `game/<id>/reset-chats` event)**, so each line carries a `chat_id` and `appendChat` de-dupes on it — chat appends must stay idempotent or the history doubles. A bot's opening greeting is **not** in the gamedata snapshot (these games carry no `chat_log` field); it arrives as a live `game/<id>/chat` event once the bot acts and is replayed at game end, so de-dup is what keeps it single. `parseGameChatLog` still recovers a snapshot `chat_log` when one is present (also funneled through `appendChat`). Because of replay=0, `GameViewModel` subscribes to events **before** calling `game/connect` so the post-connect burst isn't missed.
- **Sending chat:** `game/chat {game_id, body, move_number, type}` — the *current* protocol authenticates via the earlier `authenticate` message, so **no per-message auth/identity is needed** (the legacy `game_chat_auth`/`username`/`ranking`/`ui_class` fields the readme docs show are gone — confirmed against the goban `ClientToServer` protocol types). `type` is `"main"` for normal play. Built by `buildGameChatMessage`, sent via `OgsSocket.sendChat`; `GameViewModel` tracks `move_number` and relies on the server echoing the line back (as a `game/<id>/chat` event) to render your own message — no optimistic insert.
- **Passing & scoring:** two consecutive passes (`game/move` with `".."`) move the game to `phase:"stone removal"` — the scoring phase, **not** the end. The board freezes until a player accepts: send `game/removed_stones/accept` `{game_id, player_id, stones, strict_seki_mode}` where `stones` is the dead-stone set in OGS encoding (echo the set the server/opponent marks — sourced from gamedata `removed` and the opponent's `game/<id>/removed_stones_accepted`; the strings must match exactly or the game won't conclude). `game/removed_stones/reject` resumes play. Bots auto-accept; once both agree, `phase` → `finished` with the scored result. Skipping this leaves any game that ends by passing frozen forever.
- **Auth:** OAuth2 PKCE; the public `CLIENT_ID` in `Ogs.kt` is not a secret (PKCE, no client secret). `user_jwt` from `/api/v1/ui/config` authenticates the socket.

## Coding style

Follow `kotlin.code.style=official`. Mirror the existing files — they are consistent:

- **4-space indent; trailing commas** on multi-line argument/parameter lists.
- **Imports:** wildcard only for Compose (`androidx.compose.foundation.layout.*`, `material3.*`, `runtime.*`). Everything else (esp. `kotlinx.serialization.json` members) is imported explicitly. No wildcards outside Compose.
- **State modeling:** prefer `sealed interface` + `data object`/`data class` for UI/session state over nullable flags.
- **Comments explain *why*, not *what*** — especially the rationale behind privacy choices and OGS protocol quirks. Keep that habit; it's the house style.
- **Naming:** `*Screen` = Compose UI (body-only where a parent scaffold owns the top bar), `*ViewModel` = state holder. Private composables for a screen live in the same file.
- Compose: hoist state, pass `onX` lambdas down; ViewModels never reference Compose or Android UI types.

## Documentation and Testing Discipline

Code, its documentation, and its tests move together in the **same change** — never one without the others. This is what keeps the project from silently regressing or growing bugs outside the reach of the suite. Hold the line on all of it:

- **Extract logic so it can be tested.** Any non-trivial rule (game rules, parsing, encoding, request/response shaping) goes in a **pure top-level function** with no `Context`/`Android`/OkHttp/Compose deps — the `buildChallengeBody`, `parseActiveBots`, `replayMoves`, `parse*` pattern. If you find yourself unable to test something because it needs a socket or a `Context`, that's the signal to extract the pure core, not to skip the test. ViewModels and I/O classes should *call* these functions, not embed the logic inline.
- **Adding code → add tests.** New pure logic ships with unit tests covering the happy path **and** the edges that matter (boundaries, defaults/missing fields, the illegal/empty cases). No PR adds untested rule logic.
- **Fixing a bug → add the regression test first.** Reproduce it as a failing test, then fix it, so the bug can't come back. (e.g. a capture-count or ko miscalc gets a `replayMoves` case.)
- **Editing behavior → update the tests and the prose.** If you change what a function does, update its tests in the same commit; if you change a protocol/quirk/flow, update the relevant comment **and** the matching CLAUDE.md section (OGS cheat-sheet, Gotchas, etc.). Stale docs are worse than none.
- **Deleting code → delete its tests and docs.** Remove the now-dead tests, KDoc, and any CLAUDE.md / comment references in the same change. Don't leave orphans.
- **Keep the coverage list current.** When you add or remove a test file, update the bullet list under [Testing](#testing). Treat it as the index of what's actually guarded.
- **Document the *why*, not the *what*** (house style above) — especially privacy rationale and OGS protocol quirks. A KDoc on every public function/`data class`; a one-line `//` on any non-obvious branch.
- **What unit tests can't reach** (Compose UI, colors, real socket/REST timing), cover with the manual smoke flow below and say so in the PR — don't pretend a coverage gap doesn't exist.

## Non-negotiable constraints

These are the reason the app exists — verify any change preserves them:

- **`INTERNET` is the only permission.** No location, contacts, advertising ID, analytics, crash reporting, or third-party SDKs of that kind.
- **No analytics/telemetry**, including the *optional* OGS socket messages (`net/connects`, `net/timeout`, route latency, etc.). The `authenticate` payload stays minimal (jwt + client name; no device id / user agent).
- **Tokens encrypted at rest** (`TokenStore`); `allowBackup=false`, custom `data_extraction_rules`.
- **OAuth via Custom Tab (AppAuth), never an embedded WebView** — no token leakage.
- **Haptics use the no-permission path only** (`view.performHapticFeedback(...)` / `LocalHapticFeedback`). Do not add `VIBRATE` or use `Vibrator`/`VibrationEffect` — that would be a second permission. App info should always show zero runtime permissions.
- When adding a network call, ask: does this send more than strictly necessary? If unsure, send less.

## Testing

JVM unit tests live in `app/src/test/java/` (JUnit4, no Android framework / Robolectric needed). Run with `.\gradlew.bat testDebugUnitTest`. Current coverage:
- `board/BoardStateTest` — capture logic (single stone, multi-stone group, corner, no-capture-with-liberty) and move legality (`legality`/`koPointAfter`: occupied, suicide, capture-is-not-suicide, simple ko detected + forbidden + cleared).
- `board/MoveReplayTest` — `replayMoves`: turn alternation, per-side prisoner counts, last-move tracking, ko recovery, and pass-dissolves-ko.
- `board/OgsCoordTest` — encode/decode incl. pass and a full 19×19 round-trip.
- `net/ChallengesTest` — `formatRank` kyu/dan boundaries, `parseActiveBots` (filtering declining bots, defaults, sort), and `buildChallengeBody` JSON shape.
- `net/OgsParseTest` — `parseUiConfig` (jwt + defaults + throws without jwt), `parseActiveGames` (the `myTurn`/`current_player` logic, defaults, empty), `parseGameResult`, and `parseSentChallenges` (filtering to mine, array/`results` shapes, defaults).
- `net/GameChatTest` — `parseGameChat` (plain-text line, `message`/`line` key fallback, **translated bot bodies rendered** + translated-without-`en` skipped, skipping analysis bodies, defaults, no-message case, `chat_id`/composite identity), `parseGameChatLog` (gamedata history, skips analysis bodies, empty when absent), `buildGameChatMessage` (send payload shape, no auth/identity fields), and `appendChat` idempotency (re-sent log doesn't duplicate).

Keep pure logic in plain functions (e.g. `buildChallengeBody` lives in `net/Challenges.kt`, **not** as a method on `OgsRest`, so tests don't need a `Context`/`TokenStore`). When adding testable logic, prefer this top-level-function pattern over private members. No instrumented (`androidTest`) tests yet.

Manual smoke flow (covers the parts unit tests can't): log in → drawer → **New Game** → pick an online bot (9×9, live) → confirm it starts and renders → play a capturing sequence → resign/pass. Bot games are the fastest end-to-end check since they start immediately. Also flip **Settings → theme** and confirm it persists across a restart.

## Gotchas

- Material3 `menuAnchor()` no-arg is deprecated in 1.3 but still compiles (used in dropdowns) — a warning, not an error.
- `Stone` has three values (`EMPTY`, `BLACK`, `WHITE`); `when` over a `Stone?` color needs to handle `EMPTY`/`else`, not just BLACK/WHITE/null.
- The socket's `events` flow has `replay = 0`; subscribe *before* connecting if you need a one-shot broadcast like `active-bots`.
