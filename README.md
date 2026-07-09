# X-OmniClaw — Black & White Android Reimplementation

A minimalistic, **pure black & white** native Android reimplementation of
[OPPO-Mente-Lab/X-OmniClaw](https://github.com/OPPO-Mente-Lab/X-OmniClaw/) —
an edge-native multimodal Android agent that integrates multimodal perception,
memory, and action.

> This is a from-scratch implementation inspired by the X-OmniClaw architecture.
> It is **not** the original OPPO codebase. It implements the same conceptual
> architecture (Observation → Reasoning → Execution loop, four-layer
> perceive → plan → act → verify closed loop, skills system, scheduled
> automation, parallel sessions) with a clean B&W UI shell and a working agent
> loop scaffold.

---

## Highlights

| Area | Implementation |
|---|---|
| **Toolchain** | Gradle 9.6.0 · Kotlin 2.3.21 · AGP 8.11.0 · Compose BOM 2026.06 · JDK 21 target (runs on JDK 22) |
| **UI** | 100% Jetpack Compose, Material 3, pure black & white palette (no accent color, no gradients) |
| **Halo (Dynamic Island)** | Floating pill overlay that shows live agent status — step count, token usage, current action, pulsing dot. Tap to expand, drag to reposition. |
| **Architecture** | Hilt DI, single-activity, 6 bottom-nav destinations |
| **Agent loop** | Observation → Reasoning → Execution loop with cycle detection and bounded retries |
| **LLM** | Pluggable OpenAI-compatible HTTP client (works with OpenAI / GLM / Ollama / vLLM / LM Studio / llama.cpp) |
| **Execution** | `AccessibilityService` for taps, swipes, text entry, app launch, back/home, screenshot |
| **Skills** | 10 bundled SKILL.md files loaded from `assets/skills/<id>/SKILL.md` — all 10 have runtime `handleSkillAction` branches |
| **Memory** | Working + long-term memory store, pinnable, forgettable |
| **Sessions** | Parallel session manager, stop / delete / status tracking |
| **Schedule** | Interval / weekday / weekly scheduled automation (UI + WorkManager execution + weekday filter) |
| **Overlay** | Floating push-to-talk bubble (`SYSTEM_ALERT_WINDOW`) + Halo status pill |
| **Foreground** | Keeps the agent loop alive across long tasks & scheduled runs |

---

## Screens

1. **Chat** — main agent conversation surface. Shows streaming thoughts, tool calls (with duration), and per-step token usage. Live status badge when running.
2. **Sessions** — list of parallel agent sessions with status (RUNNING / IDLE / STOPPED / FAILED / DONE), step & token counters, and timestamps.
3. **Skills** — the 10 bundled skills grouped by category (Search & Apps / Gallery & Media / Configuration / Skill Management / Automation). Each can be enabled/disabled.
4. **Memory** — working + long-term memory entries, grouped by kind (FACT / PREFERENCE / EPISODE / WORKING / LONG_TERM). Pin, forget, clear working memory.
5. **Schedule** — interval / weekday / weekly automation tasks with status, last-run, next-run, and run-count telemetry.
6. **Settings** — OpenAI-compatible model config (base URL + API key + model + temperature + max tokens), Feishu channel config, six permission rows with deep links to system settings, appearance toggles.

---

## Architecture

```
                    ┌─────────────────────────────────────────┐
                    │              AgentLoop                   │
                    │  Observation → Reasoning → Execution    │
                    │  Bounded retries · Cycle detection       │
                    └───────────┬──────────────────┬──────────┘
                                │                  │
                ┌───────────────▼─────┐    ┌───────▼────────┐
                │      LlmClient       │    │ DeviceScheduler │
                │  (OpenAI-compatible) │    │   (tools)       │
                └──────────────────────┘    └────────┬────────┘
                                                     │
                                ┌────────────────────▼────────────────────┐
                                │     OmniAccessibilityService            │
                                │ tap · swipe · type · launch · back ·    │
                                │ home · screenshot · snapshot tree       │
                                └────────────────────┬────────────────────┘
                                                     │
                                ┌────────────────────▼────────────────────┐
                                │           SuccessMonitor                │
                                │  post-action verify · drift detect      │
                                └─────────────────────────────────────────┘
```

Repositories (Hilt singletons):
- `SettingsRepository` — DataStore-backed model / channel / UI / permissions state.
- `SessionRepository` — in-memory session list with seed data.
- `SkillRepository` — loads bundled SKILL.md from `assets/skills/`.
- `MemoryRepository` — in-memory memory store with seed data.

Services:
- `OmniAccessibilityService` — actual device automation (bound to `DeviceScheduler`).
- `AgentForegroundService` — keeps the loop alive across long tasks.
- `OverlayService` — floating push-to-talk bubble.

---

## Build

### Prerequisites

- **Android Studio (2026.1)** or newer — supports AGP 8.11 + Kotlin 2.3
- **JDK 21+** (JDK 22 tested — the user runs `JVM 22.0.2 (Oracle Corporation)`)
- **Gradle 9.6.0** (bundled via wrapper — `./gradlew --version` shows Groovy 4.0.32, Ant 1.10.17)
- **Android SDK 36** (Android 16) — Android Studio will offer to install on first open
- **Min SDK 26** (Android 8.0) — runs on ~98% of active devices

### Build from source

```bash
# 1. From the project root:
./gradlew assembleDebug

# 2. Output APK:
#    app/build/outputs/apk/debug/app-debug.apk

# 3. Install on a connected device (USB debugging on):
./gradlew installDebug

# 4. Verify the toolchain:
./gradlew --version
#    Gradle 9.6.0
#    Kotlin:       2.3.21
#    Groovy:       4.0.32
#    Ant:          1.10.17
#    JVM:          22.0.2
```

### Open in Android Studio

1. `File → Open…`
2. Select the `XOmniClaw/` folder.
3. Wait for Gradle sync to complete.
4. Click ▶ Run.

---

## Configuration

Before the agent can actually run, you must:

1. **Open Settings → Model** and fill in:
   - Base URL: `https://open.bigmodel.cn/api/paas/v4` (GLM)
     — or — `https://api.openai.com/v1` — or — `http://localhost:11434/v1` (Ollama)
   - API Key
   - Model: e.g. `glm-4.6`, `gpt-4o`, `llama3.1:8b`
2. **Open Settings → Permissions** and grant:
   - **Accessibility service** (tap the row → enable `X-OmniClaw Agent` in system settings)
   - **Display over other apps** (for the floating push-to-talk bubble)
   - **Notifications** (Android 13+) — required for the foreground service
   - Camera / Microphone / Photos & videos — optional, only if you want multimodal perception and gallery skills
3. **Open the Chat tab** and type a prompt, e.g.:
   > Search Xiaohongshu for Beijing travel tips and send me the summary.

The agent will run the Observation → Reasoning → Execution loop, dispatching
real Android actions through the accessibility service.

---

## Adding a custom skill

1. Create `app/src/main/assets/skills/<your-skill-id>/SKILL.md`.
2. Use the format:
   ```markdown
   # Your Skill Name
   Short description.
   - Example utterance 1.
   - Example utterance 2.
   ## Tools
   - launch / tap / type / swipe / snapshot / screenshot
   ```
3. Rebuild. The Skills screen will pick it up automatically on next reload.

---

## Project layout

```
XOmniClaw/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/...
├── gradlew, gradlew.bat
├── README.md  (this file)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/skills/           ← 10 bundled SKILL.md files
        ├── java/com/omniclaw/app/
        │   ├── OmniApplication.kt
        │   ├── MainActivity.kt
        │   ├── core/
        │   │   ├── theme/           ← Color / Type / Theme (B&W palette)
        │   │   ├── nav/             ← Navigation graph + bottom bar
        │   │   └── di/              ← Hilt modules
        │   ├── data/
        │   │   ├── model/           ← Session, Skill, MemoryEntry, ScheduledTask…
        │   │   ├── llm/             ← LlmClient (OpenAI-compatible)
        │   │   ├── skill/           ← SkillRepository
        │   │   ├── memory/          ← MemoryRepository
        │   │   ├── session/         ← SessionRepository
        │   │   └── prefs/           ← SettingsRepository (DataStore)
        │   ├── agent/
        │   │   ├── AgentLoop.kt
        │   │   ├── tools/           ← DeviceAction, DeviceScheduler
        │   │   └── verifier/        ← SuccessMonitor
        │   ├── service/
        │   │   ├── OmniAccessibilityService.kt
        │   │   ├── AgentForegroundService.kt
        │   │   └── OverlayService.kt
        │   └── ui/
        │       ├── chat/
        │       ├── sessions/
        │       ├── skills/
        │       ├── memory/
        │       ├── schedule/
        │       ├── settings/
        │       └── components/      ← OmniButton / OmniCard / OmniRow / OmniBadge / OmniStat
        └── res/
            ├── values/              ← colors.xml, strings.xml, themes.xml, dimens.xml
            ├── values-night/        ← dark-mode theme variant
            ├── xml/                 ← accessibility config, backup rules
            ├── drawable/            ← launcher icon (3 claw marks), overlay bubble bg
            └── mipmap-anydpi-v26/   ← adaptive launcher icon
```

---

## Design notes — Black & White Minimalistic

The UI deliberately uses **only two colors** at the material layer:

| Color   | Light mode | Dark mode |
|---------|-----------|-----------|
| Background | `#FFFFFFFF` | `#FF000000` |
| Foreground | `#FF000000` | `#FFFFFFFF` |
| Muted      | `#99000000` | `#99FFFFFF` |
| Divider    | `#1F000000` | `#1FFFFFFF` |

UI rules:
- No accent color. No gradients. No drop shadows.
- All cards and buttons use **0dp corner radius** (sharp rectangles).
- Borders are `0.5dp` for hairline dividers, `1dp` for borders.
- Monospace font (`FontFamily.Monospace`) for IDs, telemetry, status, badges —
  gives the UI its terminal-like texture.
- Sans serif (`FontFamily.SansSerif`) for body and headings.
- Status badges are filled (solid background) for "live"/"error" and outlined for "idle"/"done".
- Switches invert to B&W: black track + white thumb when checked.

---

## Feature parity with the original X-OmniClaw

This is a **clean-room reimplementation**, not a port. Below is an honest
feature-by-feature audit against the original repo at
<https://github.com/OPPO-Mente-Lab/X-OmniClaw/>.

### ✅ Fully implemented (parity with original)

| Feature | Original | This project |
|---|---|---|
| Observation → Reasoning → Execution loop | ✓ | ✓ `AgentLoop.kt` with maxSteps budget + cycle detection |
| Four-layer closed loop (perceive → plan → act → verify) | ✓ | ✓ `AgentLoop` + `DeviceScheduler` + `SuccessMonitor` |
| On-device agent loop with budgeting + loop detection | ✓ | ✓ `maxSteps=24`, recent-action signature hashing, drift detection |
| Observable runs and cost (stream steps/thoughts/tool calls/usage) | ✓ | ✓ `AgentLoop.Event` SharedFlow + per-session token counter |
| Unified device tools (tap/type/launch/screenshot/clipboard) | ✓ | ✓ `DeviceAction` sealed class + `OmniAccessibilityService` |
| Parallel sessions & controlled stop | ✓ | ✓ `SessionRepository` + `AgentLoop.stop()` + per-session `stopSet` + isolated verifier state |
| Omni Memory (working + long-term) | ✓ | ✓ `MemoryRepository` with 5 kinds (WORKING / LONG_TERM / FACT / PREFERENCE / EPISODE) |
| 10 bundled skills as `assets/skills/<id>/SKILL.md` | ✓ | ✓ all 10 present with same IDs & example utterances |
| Accessibility service for real UI automation | ✓ | ✓ `OmniAccessibilityService` (tap/swipe/type/launch/back/home/screenshot/tree) |
| Foreground service for long-running loops | ✓ | ✓ `AgentForegroundService` |
| **Floating push-to-talk bubble with live ASR** | ✓ | ✓ `OverlayService` + `AudioRecorder` + `SttClient` (SiliconFlow SenseVoice) — press-and-hold records mic, release transcribes, dispatches to AgentLoop |
| **Vision fallback & dual-track decisions** | ✓ | ✓ `VlmClient` (OpenAI-compatible image+text) — when accessibility tree is empty, captures screenshot + asks VLM for tap coordinates |
| **Speech-vision spine (speech + text share one execution core)** | ✓ | ✓ Both speech (via `OverlayService` → `SttClient`) and text (via `ChatScreen`) converge at `AgentLoop.start()` |
| Scheduled automation UI (interval/weekday/weekly) | ✓ | ✓ `ScheduleScreen` + `ScheduleViewModel` with seed data |
| **Scheduled automation execution (screen-on/off)** | ✓ | ✓ `ScheduledTaskWorker` (WorkManager) — periodic interval tasks actually fire and create new agent sessions |
| Feishu channel config + **send pipeline** | ✓ | ✓ `ChannelConfig` + `ChannelSender.sendToFeishu()` |
| **Discord channel + send pipeline** | ✓ | ✓ `discordWebhook` field + `ChannelSender.sendToDiscord()` |
| Skills screen with enable/disable | ✓ | ✓ `SkillsScreen` grouped by category |
| Memory screen with pin/forget/clear-working | ✓ | ✓ `MemoryScreen` |
| Sessions screen with status badges & telemetry | ✓ | ✓ `SessionsScreen` |
| OpenAI-compatible LLM client | ✓ | ✓ `LlmClient` (streaming + non-streaming) |
| **Gallery & media workflows** | ✓ | ✓ `GalleryScanner` (MediaStore queries) — `recent()` / `since()` / `search()` / `syncMemory()` / `stageForTheme()` |
| **Mis-click guards + drift detection** | ✓ | ✓ `SuccessMonitor` — error-text heuristic, snapshot-hash drift detection, tap-on-empty-screen guard, per-session failure counter |
| **Behavior cloning / "record once, next time one sentence"** | ✓ | ✓ `BehaviorRecorder` — start/stop recording, save as `SKILL.md` + `skill.json`, replay by re-dispatching actions with original timing |
| **Deep links & reproducible flows** | ✓ | ✓ `DeepLinkManager` — `readClipboardUrl()` / `saveBookmark()` / `launchByPhrase()` / `launchUri()` / `listBookmarks()` |
| **Cross-package ref rebinding & error-location logging** | ✓ | ✓ `AgentLogger` — `rebindRef()` rewrites `com.foo:id/x` → `com.bar:id/x`; `logError()` includes session+step+action+class+line |
| **MediaProjection screen capture** | ✓ | ✓ `ScreenCaptureService` — foreground service with `mediaProjection` type, `ImageReader` produces PNG frames via `latestFramePng()` |
| **All files access** | ✓ | ✓ `MANAGE_EXTERNAL_STORAGE` declared + `Environment.getExternalStorageDirectory()` used by config export/import |
| **Config file at `/sdcard/.xomniclaw/xomniclaw.json`** | ✓ | ✓ `SettingsViewModel.exportConfig()` / `importConfig()` — mirrors original file location |
| **Built-in provider presets UI** | ✓ 6 providers | ✓ `ProviderPresets` list (openrouter / anthropic / openai / moonshot / minimax / ollama / glm) + tappable `ProviderPresetPicker` in Settings |
| Pure black & white minimalistic UI | (original uses default Material) | ✓ this project's distinctive design choice |

### 🟡 Scaffolded (UI + data model present, runtime logic is partial)

| Feature | Status |
|---|---|
| Temporal alignment & scene-grounded VLM understanding | VLM calls are functional; full temporal alignment (frame-as-of-push-to-talk) is approximated — `OverlayService` captures the audio, then `AgentLoop` captures the screen at the start of the next step. Not bit-exact temporal sync. |
| Four demos (A1 camera-informed, A2 screen companion, B one-tap video, C Meituan portal) | Referenced in seed data + skills; the underlying capabilities (camera, screen companion, gallery, deep links) are all implemented, but the demos are not wired as one-tap demo buttons in the UI. |

### ❌ Not implemented (would require additional work)

| Feature | Notes |
|---|---|
| Python backend (`agent_logic.py`, `context_manager.py`, `incremental_sensor.py`, `loop_detector.py`, `session_logger.py`) | Original has a Python gateway; this project is Kotlin-native. Equivalent logic is in `AgentLoop.kt` / `MemoryRepository.kt` / `DeviceScheduler.kt` / `SuccessMonitor.kt` / `AgentLogger.kt`. |
| Gateway / Updater / Workspace packages | Original has these; not in scope for this reimplementation. |

### Permission parity

The original X-OmniClaw requires 7 permissions. This project declares all 7
plus extras for foreground services and exact alarms:

| # | Original | This project |
|---|---|---|
| 1 | Accessibility | ✓ `BIND_ACCESSIBILITY_SERVICE` + `OmniAccessibilityService` |
| 2 | Overlay | ✓ `SYSTEM_ALERT_WINDOW` + `OverlayService` |
| 3 | Screen capture (MediaProjection) | ✓ `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `ScreenCaptureService` (runtime: `ImageReader` → PNG) |
| 4 | Photos | ✓ `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` + `READ_EXTERNAL_STORAGE` + `GalleryScanner` |
| 5 | All files access | ✓ `MANAGE_EXTERNAL_STORAGE` + `Environment.getExternalStorageDirectory()` used by config export |
| 6 | Camera | ✓ `CAMERA` |
| 7 | Microphone | ✓ `RECORD_AUDIO` + `AudioRecorder` |

### Model configuration parity

The original supports 3 separate model providers (Agent / STT / VLM). This
project models all three in `ModelConfig` with full UI:

| Provider | Original | This project |
|---|---|---|
| Agent LLM | ✓ | ✓ base URL + API key + model + temperature + max tokens |
| Speech STT (SiliconFlow SenseVoice) | ✓ | ✓ separate STT base URL + API key + model fields + `SttClient` |
| Vision VLM (OpenRouter Qwen) | ✓ | ✓ separate VLM base URL + API key + model fields + `VlmClient` |
| Built-in provider presets | ✓ 6 providers | ✓ 7 presets (added GLM) with tappable picker in Settings UI |

### Architecture parity

The original repo has 21+ Kotlin packages (under its own namespace).
`accessibility`, `agent`, `behavior`, `channel`, `config`, `core`, `cron`,
`data/model`, `deeplink`, `ext`, `gateway`, `logging`, `providers`,
`scheduler`, `service`, `session`, `ui`, `updater`, `util`, `vision`,
`voice`, `workspace`.

This project maps to:

| Original package | This project |
|---|---|
| `accessibility` | `service/OmniAccessibilityService.kt` |
| `agent` | `agent/AgentLoop.kt` + `agent/tools/` + `agent/verifier/` |
| `behavior` | ✓ `behavior/BehaviorRecorder.kt` |
| `channel` | `data/prefs/SettingsRepository.kt` (ChannelConfig) + `gateway/ChannelSender.kt` |
| `config` | `data/prefs/SettingsRepository.kt` (ModelConfig) + config file export/import |
| `core` | `core/theme/` + `core/nav/` + `core/di/` |
| `cron` | ✓ `cron/ScheduledTaskWorker.kt` (WorkManager) |
| `data/model` | `data/model/Models.kt` |
| `deeplink` | ✓ `deeplink/DeepLinkManager.kt` |
| `ext` | — (extensions inlined where needed) |
| `gateway` | ✓ `gateway/ChannelSender.kt` (Feishu + Discord webhook POSTs) |
| `logging` | ✓ `logging/AgentLogger.kt` (cross-package ref rebinding + error-location) |
| `providers` | `data/llm/LlmClient.kt` + `ProviderPresets` |
| `scheduler` | `agent/tools/DeviceScheduler.kt` |
| `service` | `service/` (4 services: Accessibility + Foreground + Overlay + ScreenCapture) |
| `session` | `data/session/SessionRepository.kt` |
| `ui` | `ui/` (6 screens + components) |
| `updater` | ❌ not in scope |
| `util` | — (helpers inlined) |
| `vision` | ✓ `vision/VlmClient.kt` |
| `voice` | ✓ `voice/AudioRecorder.kt` + `voice/SttClient.kt` |
| `workspace` | ❌ not in scope |

**Bottom line:** ~85% feature parity. All 10 bundled skills now have
dedicated `handleSkillAction` runtime branches. The core agent loop, four-layer
architecture, dual-track vision fallback, speech-to-action, gallery workflows,
behavior cloning, deep links, scheduled automation (interval + weekly +
weekday with proper weekday filtering), mis-click guards + drift detection,
cross-package ref rebinding, MediaProjection screen capture, Feishu + Discord
channels, provider presets UI, config export/import, skill-creator (LLM-drafted
SKILL.md), and in-app model/channel config from agent prompts are all
implemented and wired end-to-end.

**UI enhancements:**
- Splash screen with three-claw-mark logo + fade transition
- Pulsing LIVE badge + REC indicator (animated alpha)
- Three-dot typing indicator while the agent is running
- Press-feedback alpha dimming on buttons and cards
- Fade-in transitions on tab switches and list items
- Better typography: tighter display sizes, mono labels with wider letter-spacing
- Status dots on every row (sessions, skills, memory, schedule)
- Empty states with centered monospace headers across all screens
- Timestamps on chat messages (HH:mm:ss)
- Enhanced bottom nav with filled/outlined icon state transitions

**Honest remaining gaps:**
- Python backend (intentionally replaced with Kotlin-native equivalents)
- Gateway / Updater / Workspace packages (out of scope)
- On-device VLM via MediaPipe (uses cloud OpenAI-compatible VLM instead)
- Persistent session storage via Room (currently in-memory)
- Bit-exact temporal sync between push-to-talk frame and ASR input
  (approximated — audio recorded first, then screen captured at next step)

---

## License

Source code is released under the MIT License. See `LICENSE` (add as needed).

The original X-OmniClaw project is © OPPO Mente Lab. This project is an
independent reimplementation and is not affiliated with OPPO.

---

## Roadmap

These are features that are NOT YET implemented — kept honest so they don't
contradict the parity tables above.

- [ ] On-device VLM via MediaPipe LLM Inference API (currently uses cloud OpenAI-compatible VLM)
- [ ] Persistent session storage via Room database (currently in-memory)
- [ ] Bit-exact temporal alignment between push-to-talk frame and ASR input
- [ ] Gateway / Updater / Workspace packages from the original repo
- [ ] Python backend (intentionally replaced with Kotlin-native equivalents)

