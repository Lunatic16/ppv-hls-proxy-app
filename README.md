# PPV HLS Proxy App

A native Android app that browses, resolves, and plays live HLS streams from ppv.s.. and its mirror domains. Built with Kotlin, Jetpack Compose, ExoPlayer (Media3), and a WebView-driven WASM decryption pipeline. Designed for sideloading on phones, tablets, and Android TV (Leanback) devices.

## Key Features

- Browse live and upcoming events from ppv.st with category tabs and text search
- Multi-source substream picker — select from available stream sources per event
- WASM-based stream decryption via in-app WebView sandbox (gasm.js / gasm.wasm)
- Local HTTP proxy that rewrites HLS playlists and strips junk from media segments
- ExoPlayer HLS playback with proxied URLs for CORS-free streaming
- API failover across ppv.st, ppv.cx, ppv.to, ppv.is, ppv.lc
- Event status badges: LIVE, SOON, 24/7 (always-live events sorted to bottom)
- Android TV / Leanback support (declared in manifest, banner/logo configured)
- Edge-to-edge Material 3 UI with dynamic color (Android 12+)

## Tech Stack

- **Language**: Kotlin 2.3.20
- **Build**: Gradle 9.1.0, Android Gradle Plugin 9.0.1, Kotlin DSL
- **UI**: Jetpack Compose (BOM 2026.03.01), Material 3
- **Navigation**: AndroidX Navigation3 1.0.1 (runtime + UI)
- **Architecture**: MVVM (ViewModel + StateFlow + Compose state)
- **Networking**: OkHttp 4.12.0, kotlinx.serialization 1.6.3
- **Playback**: AndroidX Media3 / ExoPlayer 1.4.1 (HLS support)
- **WebView**: AndroidX WebKit 1.12.0 (WebViewAssetLoader for asset access)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36
- **Java**: 17 (JVM toolchain)

## Prerequisites

- Android Studio (latest stable, or Hedgehog+ with AGP 9 support)
- Android SDK with platform 36 (compileSdk)
- JDK 17 (Gradle uses JVM toolchain 17)
- Kotlin plugin with Compose compiler support
- An Android device or emulator running API 24+ (for sideloading)

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/Lunatic16/ppv-hls-proxy-app.git
cd ppv-hls-proxy-app
```

### 2. Configure SDK Path

The `local.properties` file points to the Android SDK on your machine. It is not committed to version control. Create or edit it:

```bash
sdk.dir=/home/<user>/Android/Sdk
```

Adjust the path to match your system. On macOS it's typically `~/Library/Android/Sdk`, on Windows `%LOCALAPPDATA%\Android\Sdk`.

### 3. Build the Debug APK

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### 4. Build the Release APK (Sideload)

The release build type uses debug signing (no Play Store ceremony). Build with:

```bash
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/app-release.apk`.

### 5. Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or sideload the APK directly on your device (enable "Install from unknown sources" in settings).

### 6. Run from Android Studio

Open the project root folder in Android Studio. Let Gradle sync finish, then click Run (or use `./gradlew installDebug`).

## Architecture

### Directory Structure

```
android/
├── build.gradle.kts                  # Root Gradle config (plugin aliases)
├── settings.gradle.kts                # Project settings, repo config, rootProject name
├── gradle.properties                  # Gradle daemon, AndroidX, config cache
├── gradle/
│   ├── libs.versions.toml             # Version catalog (all dependency versions)
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties   # Gradle 9.1.0 distribution
├── gradlew / gradlew.bat             # Gradle wrapper scripts
└── app/
    ├── build.gradle.kts               # App module build config
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # Manifest (INTERNET perm, Leanback, cleartext)
        │   ├── assets/
        │   │   ├── decrypt.html        # WebView decryption sandbox
        │   │   ├── gasm.js            # WASM glue JS module
        │   │   └── gasm.wasm          # Decryption WASM binary
        │   ├── java/com/example/ppvstreamresolver/
        │   │   ├── MainActivity.kt    # Entry point, starts LocalHttpProxy
        │   │   ├── Navigation.kt       # Nav3 back stack + entry provider
        │   │   ├── NavigationKeys.kt   # NavKey data objects (Main, Player)
        │   │   ├── StreamResolver.kt   # Protobuf handshake + WebView WASM decryption
        │   │   ├── LocalHttpProxy.kt   # On-device HTTP proxy for HLS rewriting
        │   │   ├── data/
        │   │   │   ├── DataModels.kt       # Serializ dinners + domain models
        │   │   │   └── DataRepository.kt  # API fetch with domain failover
        │   │   ├── theme/
        │   │   │   ├── Color.kt           # Material 3 color definitions
        │   │   │   ├── Theme.kt           # PPVStreamResolverTheme (dynamic color)
        │   │   │   └── Type.kt            # Typography
        │   │   └── ui/
        │   │       ├── main/
        │   │       │   ├── MainScreen.kt          # Compose UI: tabs, search, event list
        │   │       │   └── MainScreenViewModel.kt  # ViewModel + UiState sealed interface
        │   │       └── player/
        │   │           └── PlayerScreen.kt        # ExoPlayer HLS playback
        │   └── res/                    # Drawables, mipmaps, strings, themes, XML configs
        ├── test/                       # Unit tests (JUnit + coroutines-test)
        └── androidTest/               # Instrumented tests (Compose UI test)
```

### Request Lifecycle

1. App launches → `MainActivity.onCreate()` starts `LocalHttpProxy` on port 3000
2. `MainScreen` collects from `MainScreenViewModel` which reads `DefaultDataRepository`
3. Repository fetches `https://api.<domain>/api/streams` with failover across 5 domains
4. JSON response deserialized via kotlinx.serialization → `StreamResponse` → `List<Event>`
5. Events sorted by state (LIVE > SOON > INFO > ENDED), 24/7 always at bottom
6. User taps an event → substream dialog → selects a source → navigates to `PlayerScreen`
7. `PlayerScreen` calls `StreamResolver.resolve(iframeUrl)`:
   - Parses embed URL to get origin and path
   - Sends protobuf POST to `{origin}/fetch` with encoded body
   - Receives `island` header + response body (protobuf)
   - Spawns a headless `WebView` loading `decrypt.html` from assets
   - WebView loads `gasm.js` + `gasm.wasm`, applies memory patches, calls `set_stream_jw()`
   - Decrypted HLS URL extracted from WASM memory via regex
   - URL returned to Kotlin via `KotlinBridge` JavascriptInterface
8. PlayerScreen constructs proxied URL: `http://127.0.0.1:3000/api/hls?url=...&embed=...&embedOrigin=...`
9. ExoPlayer plays the proxied HLS stream through `LocalHttpProxy`

### Stream Decryption Pipeline

```
iframeUrl (e.g. https://embed-domain/embed/XXXXX)
  │
  ▼
StreamResolver.resolve()
  │
  ├─ 1. Protobuf handshake POST to {origin}/fetch
  │     └─ Returns: island header + protobuf body
  │
  ├─ 2. WebView sandbox (decrypt.html)
  │     ├─ Loads gasm.js + gasm.wasm from assets (via WebViewAssetLoader)
  │     ├─ Stubs jwplayer, fetch, P2PEngineHls globals
  │     ├─ Applies memory patches (WASM linear memory offsets)
  │     ├─ Calls wasm.set_stream_jw(island, body)
  │     └─ Extracts m3u8 URL from WASM memory via regex
  │
  └─ 3. Returns DecryptResult(streamUrl, embedPath, embedOrigin)
        │
        ▼
     PlayerScreen → proxied URL → LocalHttpProxy → ExoPlayer
```

### Local HTTP Proxy (`LocalHttpProxy.kt`)

A threaded `ServerSocket` on port 3000 that serves `/api/hls`:

- **Playlist rewriting**: intercepts `.m3u8` responses, resolves relative URIs, rewrites segment and variant URLs to route back through the proxy with `Referer`/`Origin` headers
- **Segment cleaning**: strips PNG wrappers and non-TS junk from media segments by scanning for the 0x47 sync byte pattern (188-byte TS frame alignment)
- **Live playlist sync**: drops the last segment from live media playlists if its duration is below 95% of target duration (prevents incomplete-edge playback)
- **CORS**: adds `Access-Control-Allow-Origin: *` to all responses

### Data Layer

**API Domains (failover order)**:
1. `api.ppv.s..`
2. `api.ppv.c..`
3. `api.ppv.t..`
4. `api.ppv.i..`
5. `api.ppv.l..`

Each domain is tried in order; the first successful response with `success: true` is used. If all fail, the last error is thrown.

**Data Models**:

| Model | Description |
|---|---|
| `StreamResponse` | Top-level API response (`success`, `streams`, `error`) |
| `Category` | Category grouping (`category`, `streams`) |
| `StreamItem` | Raw stream entry from API (id, name, iframe, substreams, times) |
| `Substream` | Alternative source within a stream (source_tag, locale, iframe) |
| `Event` | Domain model for UI (id, name, category, startsAt, alwaysLive, substreams) |
| `Source` | Domain model for a playable source (label, locale, iframeUrl, isDefault) |

### Navigation

Uses AndroidX Navigation3 with type-safe `NavKey` data objects:
- `Main` — event list screen
- `Player(iframeUrl, title)` — player screen

### UI States

`MainScreenUiState` sealed interface:
- `Loading` — spinner shown while fetching
- `Success(data: List<Event>)` — event list with tabs + search
- `Error(throwable: Throwable)` — error message

## Available Gradle Tasks

| Command | Description |
|---|---|
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew assembleRelease` | Build release APK (debug-signed, sideload-ready) |
| `./gradlew installDebug` | Build and install debug APK on connected device |
| `./gradlew test` | Run unit tests (JUnit) |
| `./gradlew connectedAndroidTest` | Run instrumented tests (requires device/emulator) |
| `./gradlew lint` | Run Android lint |
| `./gradlew clean` | Clean build outputs |
| `./gradlew dependencies` | Print dependency tree |

## Testing

### Unit Tests

```bash
./gradlew test
```

Tests live in `app/src/test/` and cover the ViewModel's UI state transitions. Uses JUnit 4 + kotlinx-coroutines-test with a `FakeDataRepository`.

### Instrumented Tests

```bash
./gradlew connectedAndroidTest
```

Requires a connected device or running emulator. Tests in `app/src/androidTest/` use `createAndroidComposeRule` to test Compose UI components.

### Test Structure

```
app/src/
├── test/java/.../ui/main/
│   └── MainScreenViewModelTest.kt     # ViewModel state tests (JUnit)
└── androidTest/java/.../ui/main/
    └── MainScreenTest.kt              # Compose UI tests (Espresso + Compose)
```

## Android TV / Leanback Support

The manifest declares:
- `android.software.leanback` feature (not required, so it installs on phones too)
- `android.hardware.touchscreen` feature (not required, for TV remotes)
- `LEANBACK_LAUNCHER` intent category alongside `LAUNCHER`
- `android:banner` and `android:logo` on the activity for TV home screen

The app works with both touch and D-pad navigation on TV devices.

## Sideload Deployment

This app is built for sideloading only — no Play Store publication.

1. Build the release APK:
   ```bash
   ./gradlew assembleRelease
   ```
2. Transfer `app/build/outputs/apk/release/app-release.apk` to your device
3. Enable "Install from unknown sources" in Android settings
4. Open the APK file manager to install
5. Launch "PPV Stream Resolver" from your app drawer or TV home screen

## Troubleshooting

### Build Issues

**Error**: `SDK location not found`

**Solution**: Ensure `local.properties` exists with the correct `sdk.dir` path pointing to your Android SDK installation.

**Error**: `Plugin [id: 'com.android.application'] was not found`

**Solution**: Ensure you're using Gradle 9.1.0 (check `gradle/wrapper/gradle-wrapper.properties`). The project uses AGP 9.0.1 which requires a recent Gradle version.

### Playback Issues

**Error**: `Handshake request failed` or `Missing island header`

**Solution**: The embed domain may be down or the stream may be offline. The app tries 5 domains in sequence, but if all are unreachable you'll see an error. Check network connectivity and try again.

**Error**: `decrypt did not produce m3u8 url`

**Solution**: The WASM decryption failed to extract a valid HLS URL. This can happen if:
- The stream's encryption scheme has changed
- The `gasm.wasm` / `gasm.js` assets are outdated
- The WebView timed out (15s limit)

**Error**: `Invalid segment payload` in proxy logs

**Solution**: The proxy couldn't find a valid TS sync byte (0x47) in the segment data. The segment may be corrupted or encrypted in a format the stripper doesn't handle.

### Proxy Issues

**Error**: Streams don't play but events load fine

**Solution**: Verify the local proxy is running (check logcat for `Local HTTP Proxy started on port 3000`). The proxy starts in `MainActivity.onCreate()` and stops in `onDestroy()`. If the app is killed and restarted, the port should be available again after a brief OS-level cleanup window.

## Ecosystem

This app is part of a family of PPV stream resolver clients:

- [ppv_picker](https://github.com/Lunatic16/ppv-picker) — CLI Python script (original)
- [ppv-hls-proxy](https://github.com/Lunatic16/ppv-hls-proxy) — Web frontend + Node backend (full proxy server)

## License

This project is for personal use. No warranty provided.
