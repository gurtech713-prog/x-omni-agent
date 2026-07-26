# ─── General ──────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# ─── kotlinx.serialization ───────────────────────────────────────────
# Keep the companion objects of @Serializable classes (generated serializer factory)
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.omniclaw.app.**$$serializer { *; }
-keepclassmembers class com.omniclaw.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.omniclaw.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep @Serializable classes themselves
-keep @kotlinx.serialization.Serializable class ** { *; }

# ─── OkHttp / Okio ───────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Keep OkHttp platform class (loaded by reflection)
-keep class okhttp3.internal.platform.** { *; }
-keep class okio.** { *; }

# ─── Hilt / Dagger ───────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-dontwarn dagger.hilt.**

# ─── Compose ─────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── Coroutines ──────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── Room (added for persistence) ────────────────────────────────────
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.**

# ─── AndroidX Security (EncryptedSharedPreferences) ──────────────────
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.crypto.**
-dontwarn com.google.crypto.tink.**

# ─── WorkManager ─────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ─── LiteRT (on-device ML, formerly TFLite) ──────────────────────────
# The interpreter is loaded via reflection; keep the public API surface.
# Note: `litert-support` (Task Library) is intentionally not on the classpath —
# only `litert` and `litert-gpu` are pulled in (see app/build.gradle.kts).
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }   # legacy package still referenced by delegates
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.lite.**
# Native handle fields (long pointers) must not be renamed or inlined.
-keepclassmembers class com.google.ai.edge.litert.** {
    long nativeHandle;
    <fields>;
}

# ─── Google GenAI / Gemini REST (no SDK — pure OkHttp, no rules needed) ───

# ─── App domain models (serialized to Room JSON columns + DataStore) ──
# Keep data class field names so kotlinx.serialization + Room reflection
# don't break when R8 renames fields in release builds.
-keep class com.omniclaw.app.data.model.** { *; }

# ─── Hilt ViewModels (constructor params resolved via reflection) ────
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ─── Assisted-injected Hilt Workers (ScheduledTaskWorker) ─────────────
-keep class * extends androidx.hilt.work.HiltWorker { <init>(...); }
-keep @dagger.assisted.AssistedInject class * { <init>(...); }
-keep @dagger.assisted.AssistedFactory interface * { *; }

# ─── Keep enum values (Room stores them by name) ─────────────────────
-keepclassmembers enum com.omniclaw.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Release-build log stripping ─────────────────────────────────────
# Strip debug and verbose log calls in release builds to prevent secrets /
# user content from leaking to Logcat on production devices. Info/Warn/Error
# are kept (useful for crash reporting). Calls are stripped ONLY if their
# return value is unused — `Log.d(TAG, "x")` is removed; `val r = Log.d(...)`
# is preserved. The `android.util.Log` class itself is preserved so callers
# that branch on `Log.isLoggable` still work.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-keep class android.util.Log { *; }

