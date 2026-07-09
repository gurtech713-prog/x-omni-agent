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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.core.theme.OmniTheme
import com.omniclaw.app.service.ScreenCaptureService
import com.omniclaw.app.ui.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        onScreenCaptureRequested = {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
        ScreenCaptureRequestHolder.activity = this

        setContent {
            // Read the user's dark-mode preference so the Settings → Dark mode
            // toggle actually takes effect. On first launch (before the user
            // has toggled anything), fall back to the system theme so the app
            // respects the device's dark-mode setting out of the box.
            val settingsVm: SettingsViewModel = hiltViewModel()
            val ui by settingsVm.uiPrefs.collectAsStateWithLifecycle()
            OmniTheme(darkTheme = ui.darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SplashScreen { showSplash ->
                        if (!showSplash) OmniApp()
                    }
                }
            }
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
