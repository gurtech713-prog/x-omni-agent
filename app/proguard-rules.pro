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
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litert.support.** { *; }
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
