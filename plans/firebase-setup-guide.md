# Firebase Crashlytics Setup Guide

## Prerequisites

1. **Firebase Project**: Create a Firebase project at https://console.firebase.google.com/
2. **Android App Registration**: Add your Android app to the Firebase project with package name `com.aistudio.xomniclaw.mgypws`
3. **google-services.json**: Download the config file from Firebase Console

## Setup Steps

### 1. Download google-services.json

From Firebase Console:
- Go to Project Settings → Your apps
- Download `google-services.json`
- Place it in `app/google-services.json`

**Important**: Add `app/google-services.json` to `.gitignore`:
```
app/google-services.json
```

### 2. Configure Firebase Console

Enable these services in Firebase Console:
- **Crashlytics**: Automatically enabled when you add the SDK
- **Analytics**: Automatically linked with Crashlytics

### 3. Build & Test

Run a debug build to verify initialization:
```bash
./gradlew assembleDebug
```

Check Logcat for:
```
OmniApp: Firebase Crashlytics & Analytics initialized
```

### 4. Test Crash Reporting

Trigger a test crash to verify setup:
```kotlin
// Add this temporarily to test
FirebaseCrashlytics.getInstance().crash()
```

Or use the Firebase Console test button:
- Crashlytics → Test Crash → "Force a test crash"

### 5. Production Deployment

Before releasing:
- Remove test crash code
- Ensure `google-services.json` is NOT committed to version control
- Verify Crashlytics dashboard shows data after first real crash

## Monitoring

### Firebase Console Dashboard

Monitor these metrics:
- **Crash-free users**: Target > 99.5%
- **Most frequent crashes**: Address top issues first
- **ANR rate**: Monitor for unresponsive UI issues

### Custom Non-Fatal Errors

The app now records non-fatal exceptions via:
```kotlin
OmniApplication.recordNonFatal(throwable)
```

Consider adding this to:
- LLM API failures (network errors, rate limits)
- Accessibility service timeouts
- Vision pipeline failures
- Database operation errors

### Analytics Events

Track user-facing events:
```kotlin
OmniApplication.logEvent("llm_call_failed", mapOf(
    "provider" to provider.name,
    "error" to errorType
))
```

## Troubleshooting

### "Firebase initialization failed"

This warning means `google-services.json` is missing or invalid:
```
OmniApp: Firebase initialization failed (likely missing google-services.json): ...
```

**Solution**: Ensure the file exists at `app/google-services.json` and contains valid JSON.

### No crashes appearing in console

1. Wait up to 15 minutes for crash data to appear
2. Check device network connectivity
3. Verify Firebase project is active
4. Ensure app has Internet permission (already present)

### Crashlytics not tracking non-fatals

Add logging to verify:
```kotlin
Log.d("FirebaseTest", "Recording exception")
recordNonFatal(RuntimeException("Test"))
```

Check Logcat for Firebase logs:
```bash
adb logcat | grep -i firebase
```

## Privacy Considerations

Firebase automatically collects:
- Device identifiers (hashed)
- OS version
- App version
- Crash stack traces

**Does NOT collect**:
- User prompts (unless they appear in stack traces)
- API keys
- Personal data

For sensitive applications, consider:
- Disabling automatic collection of certain data
- Reviewing Firebase privacy policy
- Adding user consent flows if required by your jurisdiction

## Alternative: Without Firebase

If you prefer not to use Firebase, you can:
1. Remove the plugins from `build.gradle.kts`
2. Remove dependencies from `app/build.gradle.kts`
3. Keep the existing `installCrashHandler()` in `OmniApplication.kt`
4. Use a different crash reporter (Sentry, Bugsnag, etc.)

The app will still function without Firebase — crashes will only be visible in Logcat.
