# light-somafm

A standalone [Light Phone III](https://www.thelightphone.com/) tool for listening
to [SomaFM](https://somafm.com/), the listener-supported, commercial-free internet
radio stations. You can browse the stations, star your favorites, see what's
playing and what played recently, and keep listening after you close the tool. It
is a thin, self-contained repo built against the upstream **Light SDK**, laid out
like the other standalone tools (music-app, skylight-app) so it drops straight
into Light's tool build and review pipeline.

> **Unofficial.** This tool is not affiliated with SomaFM. It reads SomaFM's public
> JSON feeds and streams. If you enjoy the stations, consider
> [supporting SomaFM](https://somafm.com/support/).

## Layout

```
light-somafm/
├── light-sdk/            # git submodule → tthayer/light-sdk (pinned commit)
├── tool/                 # the ONLY dev-owned module
│   ├── lighttool.toml    # tool id, label, version, permissions, capabilities
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/thelightphone/somafm/**.kt
├── settings.gradle.kts   # grafts the submodule's SDK projects into this build
├── build.gradle.kts      # thin root: plugin classpath + ext build knobs
├── gradle.properties
└── gradlew, gradle/      # wrapper (matches the pinned SDK)
```

| File | Role |
|---|---|
| `SomaApi.kt` | Ktor client for the SomaFM feeds: channel list, recent songs, `.pls` resolution |
| `SomaJson.kt` | Response DTOs, `.pls` parsing, and picking a playlist for a stream quality |
| `RadioPlayer.kt` | Process-level engine over a **detached** `LightAudioPlayer`: tune, pause, stop, mirror fallback |
| `HomeScreen.kt` | Entry screen: now playing, favorites, all stations; settings button in the top bar |
| `SettingsScreen.kt` | Stream quality and reloading the station list |
| `StationScreen.kt` | A station: description, current track, play/pause/stop, favorite, recently played |
| `SomaPreferences.kt` | DataStore: quality, favorites, and the last-tuned station |

## How it uses SomaFM

| Feature | Call |
|---|---|
| Stations | `GET https://api.somafm.com/channels.json` |
| Now playing / history | `GET https://somafm.com/songs/{id}.json` (polled every 30s while a station screen is open) |
| Stream | `GET` the channel's `.pls` for the chosen quality, then play its `File1=` Icecast URL |

Every channel publishes four playlists: `mp3`/`aac` at 128k (`highest`), `aacp` 64k
(`high`), and `aacp` 32k (`low`). The quality setting (Low 32k / Standard 64k /
High 128k, Standard by default) picks one of them. The platform player can't read
`.pls` itself, so the tool resolves it into the mirror URLs (`ice1`, `ice2`, ...).
If a mirror fails with a network error, the next one is tried before an error is
shown.

## Playback

Playback uses the SDK's **detached** audio mode (`capabilities = ["detached-audio"]`
in `lighttool.toml`), so the stream keeps playing after the tool is closed and shows
up in the system media controls. When the tool is reopened it reconnects to the
running session and labels it with the last station it tuned. Pausing and resuming
re-queues the stream, which puts you back at the live edge instead of replaying a
stale buffer. **Stop** ends the detached session.

## Building locally

Requires JDK 17 and an Android SDK (`sdk.dir` in `local.properties`).

```bash
git clone --recurse-submodules git@github.com:tthayer/light-somafm.git
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
# → tool/build/outputs/apk/debug/tool-debug.apk
```

The APK is signed with the shared Light dev keystore (from the submodule) for
local sideloading. For the LightOS emulator, build with `-PwithEmulator` and set
`serverPackage = "com.thelightphone.sdk.emulator"` in `tool/lighttool.toml` locally
(don't commit it).

## Bumping the SDK

```bash
git -C light-sdk fetch origin
git -C light-sdk checkout <commit-or-tag>
git add light-sdk && git commit -m "bump light-sdk to <ref>"
```

## Releases (CI)

`.github/workflows/release.yml` (inherited from music-app and skylight-app) cuts a
GitHub Release on every merge to `main`. It derives the version from conventional
commits and attaches a dev-signed release APK.
