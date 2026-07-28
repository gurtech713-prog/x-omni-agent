package com.omniclaw.app.agent.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.omniclaw.app.accessibility.AccessibilityExecutor
import com.omniclaw.app.service.OmniAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified device-tool surface — one entry point for the agent loop to:
 *   - snapshot the current screen (accessibility tree or screenshot)
 *   - dispatch atomic Android actions (tap, swipe, type, launch, back, home)
 *
 * Delegates to [AccessibilityExecutor] for all operations. The executor
 * coordinates [com.omniclaw.app.accessibility.NodeSearchEngine],
 * [com.omniclaw.app.accessibility.GestureManager],
 * [com.omniclaw.app.accessibility.WindowTracker], and
 * [com.omniclaw.app.accessibility.AccessibilityMetrics] internally.
 *
 * If the a11y service is not connected, calls return gracefully so the
 * agent loop can still reason about screenshots-only fallback (per the
 * X-OmniClaw "vision fallback & dual-track decisions" feature).
 *
 * CRITICAL FIX (agent not performing tasks): Launch actions now work
 * WITHOUT the accessibility service by using the app Context's
 * PackageManager + startActivity. Previously, if the a11y service wasn't
 * connected, launch() returned false and the agent couldn't open ANY app.
 */
@Singleton
class DeviceScheduler @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val executor: AccessibilityExecutor,
) {

    @Volatile
    var boundService: OmniAccessibilityService? = null
        set(value) {
            field = value
            // Keep the executor's service reference in sync so it can
            // dispatch gestures + read the tree.
            executor.service = value
        }

    init {
        // Register this scheduler with the executor so the executor can read
        // the bound service via the back-reference (handles the case where
        // the service connects before the executor is wired up).
        executor.deviceScheduler = this
    }

    /** Returns a flat text representation of the current UI tree. */
    suspend fun snapshot(): String {
        val svc = boundService ?: return "(accessibility service not connected)"
        return executor.snapshot()
    }

    /**
     * Synchronous snapshot for non-suspend callers (e.g. [SuccessMonitor.verifyLast]).
     *
     * Runs the snapshot on the accessibility service's main looper via
     * [OmniAccessibilityService.snapshotTree], which is already synchronous.
     * Does NOT benefit from the executor's retry / root-recovery logic —
     * callers that need those guarantees should use the suspend [snapshot].
     */
    fun snapshotBlocking(): String {
        val svc = boundService ?: return "(accessibility service not connected)"
        return svc.snapshotTree() ?: "(empty tree)"
    }

    /** Returns the raw bitmap bytes of the latest screenshot, or null. */
    suspend fun screenshot(): ByteArray? = executor.screenshot()

    suspend fun dispatch(action: DeviceAction): Boolean {
        // CRITICAL FIX (agent not performing tasks): Launch actions do NOT
        // require the accessibility service — they use PackageManager +
        // startActivity, which work from any Context. Previously, if the a11y
        // service wasn't connected, launch() returned false silently, and the
        // agent couldn't open ANY app — even though launch is the one action
        // that should always work. Now Launch works via the app Context even
        // when the a11y service is off.
        if (action is DeviceAction.Launch) {
            val svc = boundService
            if (svc != null) {
                // A11y service connected — use the executor (which has the
                // knownMap + launchableApps cache + diagnostics).
                return executor.dispatch(action)
            }
            // A11y service NOT connected — use the app Context directly.
            return launchWithoutA11y(action.packageName)
        }

        val svc = boundService
        if (svc == null) {
            // CRITICAL FIX (agentic tasks not working): log when the a11y
            // service is not connected so the user/developer can see WHY
            // every action returns false. Previously this was a silent
            // return — the agent loop reported "error" to the LLM with no
            // diagnostic, and the user had no idea the accessibility
            // service needed to be enabled in system Settings.
            Log.w("DeviceScheduler", "dispatch($action) FAILED: accessibility service not connected. Enable it in Settings → Accessibility → X-OmniClaw.")
            return false
        }
        return executor.dispatch(action)
    }

    /**
     * Launch an app by package name WITHOUT requiring the accessibility service.
     * Uses the app Context's PackageManager + startActivity. This is the
     * fallback when the a11y service isn't connected yet (common on first
     * launch or after the user disabled/re-enabled it).
     *
     * CRITICAL FIX (agent not performing tasks): previously launch() required
     * the a11y service, so if the service wasn't connected, the agent couldn't
     * open ANY app — even though launch only needs PackageManager. Now launch
     * works even without the a11y service, so the agent can always open apps.
     * Tap/swipe/type still require the a11y service (they need gesture dispatch
     * + node access), but launch is the most common action and should always work.
     *
     * Returns true if the launch intent was successfully dispatched.
     */
    private fun launchWithoutA11y(packageNameOrAppName: String): Boolean {
        return try {
            val pm = appContext.packageManager
            val cleanedInput = packageNameOrAppName.trim().trim('"', '\'').lowercase()

            // Same knownMap as AccessibilityExecutor.launchPackage — kept in
            // sync so launch works the same way with or without the a11y service.
            val knownMap = mapOf(
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "browser" to "com.android.chrome",
                "settings" to "com.android.settings",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "telegram" to "org.telegram.messenger",
                "spotify" to "com.spotify.music",
                "maps" to "com.google.android.apps.maps",
                "gmail" to "com.google.android.gm",
                "calculator" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "photos" to "com.google.android.apps.photos",
                "gallery" to "com.google.android.apps.photos",
                "camera" to "com.android.camera",
            )

            // Camera has multiple possible package names across OEMs. Try them
            // all when the user asks for "camera".
            val cameraPackages = listOf(
                "com.android.camera",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",          // Samsung
                "com.miui.camera",                      // Xiaomi MIUI
                "org.lineageos.camera",                 // LineageOS
                "com.android.camera2",                  // AOSP camera2
                "com.oppo.camera",                      // OPPO
                "com.coloros.camera",                   // ColorOS (Realme/OPPO)
            )

            val pkg = knownMap[cleanedInput] ?: packageNameOrAppName.trim().trim('"', '\'')

            // Try the resolved package first.
            var intent = pm.getLaunchIntentForPackage(pkg)

            // If camera and the first package didn't resolve, try the other OEM camera packages.
            if (intent == null && cleanedInput == "camera") {
                for (cameraPkg in cameraPackages) {
                    intent = pm.getLaunchIntentForPackage(cameraPkg)
                    if (intent != null) break
                }
            }

            // If still null and input looks like an app label (no dot), search
            // launchable apps via a single cached query.
            if (intent == null && !cleanedInput.contains('.')) {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        launcherIntent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcherIntent, 0)
                }
                val matchPkg = resolveInfos.firstOrNull { info ->
                    runCatching {
                        info.loadLabel(pm).toString().lowercase()
                    }.getOrDefault("").let { label ->
                        label == cleanedInput || label.contains(cleanedInput)
                    }
                }?.activityInfo?.packageName
                if (matchPkg != null) {
                    intent = pm.getLaunchIntentForPackage(matchPkg)
                }
            }

            if (intent == null) {
                Log.w("DeviceScheduler", "launchWithoutA11y($packageNameOrAppName) failed: no launch intent found (tried pkg='$pkg')")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            Log.i("DeviceScheduler", "launchWithoutA11y($packageNameOrAppName) -> launched $pkg")
            true
        } catch (e: Exception) {
            Log.w("DeviceScheduler", "launchWithoutA11y($packageNameOrAppName) failed: ${e.message}")
            false
        }
    }

    /**
     * Synchronous dispatch for non-suspend callers (BehaviorRecorder replay
     * legacy path). Prefer the suspend [dispatch] for new code.
     */
    fun dispatchBlocking(action: DeviceAction): Boolean {
        val svc = boundService ?: return false
        return when (action) {
            is DeviceAction.Tap -> kotlinx.coroutines.runBlocking { svc.tap(action.x, action.y) }
            is DeviceAction.Swipe -> kotlinx.coroutines.runBlocking { svc.swipe(action.x1, action.y1, action.x2, action.y2) }
            is DeviceAction.Type -> svc.type(action.text)
            is DeviceAction.Launch -> svc.launch(action.packageName)
            DeviceAction.Back -> svc.back()
            DeviceAction.Home -> svc.home()
            DeviceAction.Screenshot -> true
            DeviceAction.NoOp -> true
        }
    }
}
