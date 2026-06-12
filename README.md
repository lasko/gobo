# Gobo — a privacy-respecting Android client for Online-Go.com

A native Kotlin/Compose app for playing Go on [OGS](https://online-go.com).

## Features

- **Sign in with your OGS account** via OAuth2 + PKCE (Custom Tab, never an embedded WebView).
- **My Games** — your active games with a "your turn" indicator.
- **Play in realtime** — board updates, captures, pass, resign, and the two-pass stone-removal / scoring flow (accept or resume) over the OGS socket.
- **Immediate move feedback** — illegal taps (occupied points, suicide, simple ko) flash locally instead of waiting for the server, plus an optional two-tap "confirm moves" mode.
- **Start a game** — challenge a bot that's currently online and accepting, or post an open challenge for a human, with full control over board size, rules, time settings, handicap, and komi.
- **Light / Dark / System theme**, chosen in Settings and remembered across restarts.

## Privacy posture

This is a deliberate design goal, not an afterthought:

- **One permission: `INTERNET`.** No location, contacts, camera, or advertising ID.
- **No analytics or tracking SDKs.** No Firebase, no crash reporters, no ad libraries. The dependency list is short on purpose so it can be audited at a glance.
- **No embedded WebView for login.** OAuth uses a Chrome Custom Tab via AppAuth, so the app never sees your OGS password, and the PKCE code verifier never leaves the device.
- **Minimal data at rest.** Only OAuth tokens are stored, encrypted (`EncryptedSharedPreferences`). They're excluded from cloud backup and device-to-device transfer.
- **No optional telemetry sent to OGS.** The realtime protocol allows analytics messages (`net/connects`, `net/timeout`, `net/route_latency`, `net/unrecoverable_error`) and optional `device_id`/`user_agent` fields in `authenticate`. Gobo sends none of them.
- **Scopes limited to `read write`** — what's needed to view and play games, nothing more.

## Build & run

**Requirements:** Android Studio (recent), JDK 17, Android SDK Platform 36. `minSdk` is 26.

1. Open this directory (the Gradle root) in Android Studio and let Gradle sync.
2. **Run** on a device or emulator, then tap **Sign in with OGS** and authorize in the browser tab.

A public OAuth client ID is already included in `net/Ogs.kt` (it's a PKCE client, so there's no secret to protect). To use your own, register a **Public** OAuth2 app at <https://online-go.com/oauth2/applications/> with redirect URI `gobo://oauth` and grant type *Authorization code*, then replace `CLIENT_ID`.

### Command line

`local.properties` (with your `sdk.dir`) and a JDK on `JAVA_HOME` are required — Android Studio's bundled JBR works:

```bash
./gradlew assembleDebug      # build a debug APK
./gradlew installDebug       # build + install on a connected device
./gradlew testDebugUnitTest  # run unit tests
./gradlew lint               # Android Lint
```

On Windows PowerShell, set `JAVA_HOME` first, e.g.
`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, then `.\gradlew.bat ...`.

## How auth works (the non-obvious part)

OGS's WebSocket `authenticate` message does **not** take the OAuth access token. It takes a `user_jwt` that you obtain by calling `GET /api/v1/ui/config` with the bearer token. So the chain is:

```
OAuth (PKCE) -> access_token
access_token -> GET /api/v1/ui/config -> user_jwt
user_jwt -> socket "authenticate"
socket -> "game/connect" -> play
```

## Architecture

| Area | File |
|------|------|
| Endpoints | `net/Ogs.kt` |
| OAuth + PKCE | `auth/AuthManager.kt` |
| Token storage | `auth/TokenStore.kt` |
| REST calls (ui/config → jwt, overview, challenges) | `net/OgsRest.kt` |
| REST response parsers (pure) | `net/OgsParse.kt` |
| Realtime protocol | `net/OgsSocket.kt` |
| Challenge/bot models, `active-bots` parser, challenge body | `net/Challenges.kt` |
| Board model, capture/legality (suicide, ko) + coords | `board/BoardState.kt` |
| Snapshot/move replay (captures, ko, turn) | `board/MoveReplay.kt` |
| Board rendering | `board/GoBoard.kt` |
| Game list | `ui/GameListScreen.kt`, `ui/GameListViewModel.kt` |
| New game / challenge | `ui/NewGameScreen.kt`, `ui/NewGameViewModel.kt` |
| In-game wiring | `ui/GameViewModel.kt` |
| Settings + theme | `settings/SettingsStore.kt`, `ui/Theme.kt`, `ui/SettingsScreen.kt` |
| App shell / nav | `ui/MainActivity.kt` |

See [CLAUDE.md](CLAUDE.md) for full architecture notes, the OGS API cheat-sheet, and coding conventions.

## Tests

JVM unit tests (no emulator needed) cover the board capture logic and move legality (suicide, ko), snapshot/move replay, the OGS coordinate codec, online-bot parsing, challenge-body construction, and REST response parsing:

```bash
./gradlew testDebugUnitTest
```

## Status & known gaps

Gobo is a working client, not a finished one. The board applies server-authoritative state and computes captures and move legality (suicide, simple ko) locally for responsiveness, and the stone-removal / scoring phase has a basic accept-and-resume flow. Full Go rule enforcement is otherwise left to the server — for a complete local engine, reference or port [goban](https://github.com/online-go/goban), the official TypeScript engine OGS itself uses, rather than rolling your own.

Planned features and known gaps are tracked as [GitHub Issues](https://github.com/lasko/gobo/issues), not duplicated here.

## Contributing

Issues and PRs welcome. Please keep the [privacy posture](#privacy-posture) intact — any change that adds a permission, an SDK, or extra data over the wire needs a clear justification. Match the existing code style (see [CLAUDE.md](CLAUDE.md)).

## Disclaimer

Gobo is an independent, unofficial client and is not affiliated with or endorsed by online-go.com / the Online-Go Server.

## License

[MIT](LICENSE) © 2026 Lasko `<lasko@nastyninja.net>`
