# XOmniClaw

Edge-native multimodal Android agent with self-learning capabilities.

## Architecture Overview

XOmniClaw implements a four-layer closed-loop agent system:

1. **Perception** - Accessibility tree + vision fallback
2. **Reasoning** - LLM-powered decision making
3. **Execution** - Device automation via accessibility service
4. **Verification** - Success monitoring and learning

### Key Components

- **AgentLoop**: Central orchestration engine implementing observe→reason→execute→verify loop
- **AccessibilityService**: UI tree parsing and gesture dispatch
- **VisionPipeline**: Screenshot analysis via VLM models
- **LearningEngine**: Self-improvement through episode reflection
- **SessionRepository**: Persistent chat history and session management

### Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with Hilt DI
- **Database**: Room with WAL mode
- **Networking**: OkHttp 4.x
- **ML**: LiteRT (on-device inference)

## Development Setup

### Requirements

- Android Studio Hedgehog or later
- JDK 21
- Android SDK 36 (compileSdk/targetSdk)
- Android 8.0+ device or emulator (minSdk 26)

### Initial Setup

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle dependencies
4. Configure LLM API keys in Settings
5. Enable Accessibility Service in device settings

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Project Structure

```
app/src/main/java/com/omniclaw/app/
├── agent/              # Agent loop and learning engine
├── accessibility/      # Accessibility service implementation
├── data/              # Data layer (repositories, database, LLM clients)
├── service/           # Foreground services and overlays
├── ui/                # Compose UI screens and ViewModels
└── vision/            # Vision pipeline and image processing
```

## Key Features

- **Multimodal Input**: Text, voice, and visual understanding
- **Self-Learning**: Extracts lessons from successful/failed episodes
- **Skill System**: Reusable automation patterns
- **Privacy-First**: Local inference option, encrypted key storage
- **Cross-Provider**: OpenAI, Gemini, Anthropic, local models

## Configuration

### LLM Providers

Configure in Settings → AI Core:
- OpenAI-compatible endpoints (OpenRouter, Ollama, etc.)
- Google Gemini (native API)
- Local models via LiteRT

### Permissions

Required permissions:
- Accessibility Service (primary automation)
- Display over other apps (Halo overlay)
- Media Projection (screen capture for vision)
- Microphone (voice input)
- Photos & videos (gallery memory)

## Testing

### Unit Tests

Located in `app/src/test/java/`:
- Core logic tests
- Repository tests
- ViewModel tests

### Instrumented Tests

Located in `app/src/androidTest/java/`:
- Integration tests
- UI tests (planned)

## Security

- API keys stored in EncryptedSharedPreferences
- Sensitive data filtered from accessibility tree
- Log redaction prevents credential leakage
- Privacy disclosure required before cloud LLM usage

## Roadmap

See [`plans/development-roadmap-v2.md`](plans/development-roadmap-v2.md) for current development priorities.

## License

Apache License 2.0 - See LICENSE file for details.

## Contributing

This is an early-stage project. Focus areas:
1. Production stability and crash reduction
2. Testing coverage expansion
3. Performance optimization
4. Additional skill integrations

## Support

For issues and questions, please open a GitHub issue.
