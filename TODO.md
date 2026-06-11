# Gobo — Roadmap & TODOs

Deferred work and known limitations, captured for later. Keep the
[non-negotiable privacy constraints](CLAUDE.md#non-negotiable-constraints) in mind
for every item (INTERNET-only permission, no third-party SDKs/telemetry).

## Planned features

### Move clocks / timers
- Surface each player's clock from the OGS `game/<id>/clock` events (already received
  by `OgsSocket`; currently ignored in `GameViewModel`).
- Render the countdown with **monospaced numerals** (e.g. a bundled `Roboto Mono`,
  added the same local way as Poppins — never the downloadable-fonts API) so the
  layout doesn't shift tick-to-tick.
- Show in the top-bar player tags next to name/captures; handle byoyomi/fischer/simple
  display from the time-control system.

### Undo
- Send `game/undo/request` (and handle the opponent's accept) — bots usually accept.
- Only valid on your own last move; disable otherwise.
- **Design decision still open:** put Undo as a button in the existing action row
  (next to Pass/Resign, minimal change) **vs.** the spec's floating pill bottom-nav
  (Undo / Pass / Resign), which is a larger layout change. Decide before building.

## Known limitations

- **Invalid-move detection is occupied-point only.** Tapping an occupied intersection
  flashes red locally; suicide and ko are not detected client-side (the server rejects
  them, but with no local flash). Full local rules would be needed for complete feedback.
- **Scoring accept (dead stones) not verified end-to-end.** The stone-removal flow
  (`Accept`/`Resume`) is implemented to the OGS spec and works for simple/empty endgames
  (which auto-resolve), but the path where the human must accept a *disputed* dead-stone
  set was never reproduced against a live bot. Verify with a real game that leaves dead
  groups; confirm the `stones` string matches what the server expects.
- **Captures are counted from observed moves.** Accurate for games played in-app from
  move 1; on mid-game reconnect they're recomputed by replaying the move list, which can
  differ from the server in rare rules edge cases.

## Maybe / nice-to-have

- Chat (currently we connect with `chat = false` for privacy/simplicity).
- Spectating / reviewing finished games.
- Convert these items to GitHub Issues for tracking.
