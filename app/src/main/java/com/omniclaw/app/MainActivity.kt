package com.omniclaw.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniclaw.app.core.nav.OmniApp
import com.omniclaw.app.deeplink.DeepLinkHandler
import com.omniclaw.app.deeplink.DeepLinkResult
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.core.theme.OmniTheme
import com.omniclaw.app.service.ScreenCaptureService
import com.omniclaw.app.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.util.Log

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinkHandler: DeepLinkHandler

    /** Activity-result launcher for MediaProjection permission. */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.startWithPermission(this, result.data!!)
        }
    }

    /** Companion holder so the SettingsScreen can request screen-capture permission. */
    private var onScreenCaptureRequested: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
            WindowCompat.setDecorFitsSystemWindows(window, false)

            onScreenCaptureRequested = {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            ScreenCaptureRequestHolder.activity = this

            setContent {
                val settingsVm: SettingsViewModel = hiltViewModel()
                val ui by settingsVm.uiPrefs.collectAsStateWithLifecycle()
                val privacyAccepted by settingsVm.privacyAccepted.collectAsStateWithLifecycle()
                OmniTheme(darkTheme = ui.darkMode) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when (privacyAccepted) {
                            null -> {
                                // Loading preferences from disk — render theme background to prevent screen flash
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background)
                                )
                            }
                            false -> {
                                PrivacyDisclosureScreen {
                                    // User accepted
                                }
                            }
                            true -> {
                                SplashScreen { showSplash ->
                                    if (!showSplash) OmniApp()
                                }
                            }
                        }
                    }
                }
            }
            // C-13: process a cold-start deep link (e.g. omniclaw://session/{id}).
            handleDeepLink(intent)
        } catch (e: Throwable) {
            Log.e("MainActivity", "Error in onCreate", e)
        }
    }

    /**
     * C-13: route a new intent into the deep-link handler. Called for both
     * cold starts (onCreate) and singleInstance re-launches (onNewIntent).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        when (val result = deepLinkHandler.handleIntent(intent)) {
            is DeepLinkResult.SESSION_OPEN ->
                Log.d("MainActivity", "Deep link -> open session ${'$'}{result.sessionId}")
            DeepLinkResult.INVALID ->
                Log.w("MainActivity", "Deep link -> invalid / unparseable")
            DeepLinkResult.NONE -> Unit
        }
    }

    override fun onDestroy() {
        if (ScreenCaptureRequestHolder.activity === this) {
            ScreenCaptureRequestHolder.activity = null
        }
        super.onDestroy()
    }

    fun requestScreenCapture() {
        onScreenCaptureRequested?.invoke()
    }
}

/**
 * Privacy disclosure screen — shown on first launch before the main app.
 *
 * Explains that cloud LLM calls send prompts and agent decisions to external
 * servers. Local/on-device models (LiteRT) do NOT send data externally.
 * The user must accept before proceeding.
 */
@Composable
private fun PrivacyDisclosureScreen(onAccept: () -> Unit) {
    val ctx = LocalContext.current
    val vm: SettingsViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Privacy Notice",
            fontFamily = OmniMono,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = """
                This app connects to cloud LLM providers (OpenAI, Gemini, OpenRouter, etc.).
                
                When enabled, your prompts and the agent's reasoning steps are sent to those servers for processing.
                
                Local/on-device models (LiteRT) do NOT send data externally.
                
                Do you accept?
            """.trimIndent(),
            fontFamily = OmniMono,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                scope.launch {
                    vm.acceptPrivacy()
                    onAccept()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I Understand — Accept")
        }
    }
}

/**
 * Minimal splash screen — shows the three-claw-mark logo + app name for ~800ms,
 * then fades out to reveal the main app.
 */
@Composable
private fun SplashScreen(content: @Composable (showSplash: Boolean) -> Unit) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(800)
        showSplash = false
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Allow tap-to-dismiss so users don't wait the full 800ms.
            .pointerInput(showSplash) {
                if (showSplash) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                        showSplash = false
                    }
                }
            },
    ) {
        // Compose the main content underneath the splash so it's ready
        // the instant the splash fades out (no cold-start jank on reveal).
        content(showSplash)
        AnimatedVisibility(
            visible = showSplash,
            enter = fadeIn(tween(Motion.DurationFast)),
            exit = fadeOut(tween(Motion.DurationMedium, easing = Motion.StandardEasing)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                // Three claw marks — pure white squares on black.
                // Semantics: announce the app name for TalkBack users.
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.semantics { contentDescription = "X-OmniClaw logo" },
                ) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(width = 10.dp, height = 36.dp)
                                .background(MaterialTheme.colorScheme.onBackground)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.splash_title),
                    fontFamily = OmniMono,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.splash_subtitle),
                    fontFamily = OmniMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Process-wide holder so the SettingsScreen can reach the current Activity
 * to request MediaProjection permission (which must be launched from an
 * Activity, not a Service).
 */
object ScreenCaptureRequestHolder {
    @Volatile var activity: MainActivity? = null
    fun request() {
        activity?.requestScreenCapture()
    }
}
