package com.omniclaw.app.ui.settings

import android.util.Log
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.os.Environment
import com.omniclaw.app.data.prefs.PermissionsState
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.data.prefs.ChannelConfig
import com.omniclaw.app.data.prefs.GeminiModels
import com.omniclaw.app.data.prefs.ModelConfig
import com.omniclaw.app.data.prefs.ProviderPresets
import com.omniclaw.app.service.OmniAccessibilityService
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniButton
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniRow
import com.omniclaw.app.ui.components.OmniTopBar
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

private enum class SettingsTab(val label: String) {
    AI_CORE("AI Core"),
    CHANNELS("Channels"),
    PERMISSIONS("Permissions"),
    SYSTEM("System")
}

private val ProviderModels: Map<String, List<String>> = mapOf(
    "openai" to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo", "o1-preview", "o1-mini"),
    "anthropic" to listOf("claude-3-5-sonnet-latest", "claude-3-opus-latest", "claude-3-haiku-latest"),
    "glm" to listOf("glm-4.6", "glm-4-flash", "glm-4-plus"),
    "gemini" to GeminiModels,
    "openrouter" to listOf("qwen/qwen3.6-flash", "meta-llama/llama-3.1-70b-instruct", "google/gemini-2.5-flash", "anthropic/claude-3.5-sonnet"),
    "nvidia" to listOf("meta/llama-3.1-70b-instruct", "nvidia/nemotron-4-340b-instruct", "mistralai/mistral-large-2-instruct"),
    "ollama" to listOf("llama3.1:8b", "gemma2:9b", "phi3", "mistral"),
    "minimax" to listOf("MiniMax-M2.5", "abab6.5g-chat"),
    "moonshot" to listOf("kimi-k2.5", "moonshot-v1-8k", "moonshot-v1-32k"),
    "litert" to listOf("local-gemma:models/gemma-2b.tflite")
)

private fun getActiveProviderId(cfg: ModelConfig): String {
    return when (cfg.provider) {
        com.omniclaw.app.data.prefs.LlmProvider.GEMINI -> "gemini"
        com.omniclaw.app.data.prefs.LlmProvider.LITERT -> "litert"
        com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT -> {
            ProviderPresets.find { it.baseUrl.trim().lowercase() == cfg.baseUrl.trim().lowercase() }?.id ?: "custom"
        }
    }
}

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = hiltViewModel()
    val ctx = LocalContext.current
    val model by vm.modelConfig.collectAsStateWithLifecycle()
    val channel by vm.channelConfig.collectAsStateWithLifecycle()
    val ui by vm.uiPrefs.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val privacyAccepted by vm.privacyAccepted.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // M-46 FIX: rememberSaveable survives process-death restoration, and the
    // sync LaunchedEffect below re-reads the volatile service state so the
    // toggle can't show a stale ON after the service is killed externally.
    // (HaloOverlayService currently exposes only isRunning(), not a StateFlow,
    // so we re-poll on a short interval rather than collect a flow.)
    var haloEnabled by rememberSaveable { mutableStateOf(com.omniclaw.app.service.HaloOverlayService.isRunning()) }

    LaunchedEffect(Unit) {
        Log.d(TAG, "SettingsScreen composed")
    }

    // M-46 FIX: keep the Halo toggle in sync with the real service state so an
    // external kill (crash / system stop) flips it off instead of leaving a
    // stale ON. Re-reads isRunning() periodically.
    LaunchedEffect(Unit) {
        while (true) {
            haloEnabled = com.omniclaw.app.service.HaloOverlayService.isRunning()
            kotlinx.coroutines.delay(1000)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val systemAccessibility = OmniAccessibilityService.isEnabled(ctx)
            val systemOverlay = android.provider.Settings.canDrawOverlays(ctx)
            val systemCamera = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val systemMic = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
            val systemMedia = ContextCompat.checkSelfPermission(ctx, mediaPerm) == PackageManager.PERMISSION_GRANTED
            val systemNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val systemScreenCapture = com.omniclaw.app.service.ScreenCaptureService.isRunning()

            vm.setPermissions(
                PermissionsState(
                    accessibility = systemAccessibility,
                    overlay = systemOverlay,
                    camera = systemCamera,
                    mic = systemMic,
                    media = systemMedia,
                    notifications = systemNotifications,
                    screenCapture = systemScreenCapture,
                )
            )
        }
    }

    val currentPerms by rememberUpdatedState(perms)
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { g -> scope.launch { vm.setPermissions(currentPerms.copy(camera = g)) } }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { g -> scope.launch { vm.setPermissions(currentPerms.copy(mic = g)) } }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { g -> scope.launch { vm.setPermissions(currentPerms.copy(media = g)) } }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { g -> scope.launch { vm.setPermissions(currentPerms.copy(notifications = g)) } }

    var modelDirty by rememberSaveable { mutableStateOf(false) }
    var channelDirty by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(SettingsTab.AI_CORE) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OmniTopBar(title = "Settings", subtitle = "v1.1.0 · edge-native")
        OmniDivider()

        // B&W Tab selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SettingsTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { 
                            Log.d(TAG, "Tab switched to: $tab")
                            activeTab = tab 
                        }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.label.uppercase(),
                        fontFamily = OmniMono,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(if (selected) MaterialTheme.colorScheme.onBackground else androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
        OmniDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (activeTab) {
                SettingsTab.AI_CORE -> {
                    SettingsCard(title = "AI Core Configuration") {
                        ModelConfigEditor(
                            cfg = model,
                            onChange = { cfg -> scope.launch { vm.setModel(cfg) } },
                            onProviderChanged = { p, b, m -> scope.launch { vm.changeProvider(p, b, m) } },
                            onDirty = { modelDirty = it },
                        )
                    }
                }
                SettingsTab.CHANNELS -> {
                    SettingsCard(title = "Notification Channels") {
                        ChannelConfigEditor(
                            cfg = channel,
                            onChange = { cfg -> scope.launch { vm.setChannel(cfg) } },
                            onDirty = { channelDirty = it },
                            onTestSend = { vm.testChannels("X-OmniClaw test message — channel config OK.") },
                        )
                    }
                }
                SettingsTab.PERMISSIONS -> {
                    SettingsCard(title = "Required System Permissions") {
                        PermissionRow(
                            title = "Accessibility service",
                            subtitle = "Inspect UI tree & dispatch taps/swipes/type.",
                            granted = perms.accessibility,
                            onOpen = {
                                Log.i(TAG, "Requesting Accessibility service permission")
                                OmniAccessibilityService.openSettings(ctx)
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Display over other apps",
                            subtitle = "Floating push-to-talk bubble.",
                            granted = perms.overlay,
                            onOpen = {
                                Log.i(TAG, "Requesting overlay permission")
                                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(i)
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Camera",
                            subtitle = "Multimodal perception: real-world frames.",
                            granted = perms.camera,
                            onOpen = {
                                Log.i(TAG, "Requesting Camera permission")
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    openAppSettings(ctx)
                                } else {
                                    cameraLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Microphone",
                            subtitle = "Speech-to-action via ASR.",
                            granted = perms.mic,
                            onOpen = {
                                Log.i(TAG, "Requesting Microphone permission")
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    openAppSettings(ctx)
                                } else {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Screen capture (MediaProjection)",
                            subtitle = "Continuous screenshot stream for vision fallback.",
                            granted = perms.screenCapture,
                            onOpen = {
                                Log.i(TAG, "Requesting Screen capture (MediaProjection) permission")
                                com.omniclaw.app.ScreenCaptureRequestHolder.request()
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Photos & videos",
                            subtitle = "Gallery memory & one-tap video skill.",
                            granted = perms.media,
                            onOpen = {
                                Log.i(TAG, "Requesting Photos & videos permission")
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                                if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) {
                                    openAppSettings(ctx)
                                } else {
                                    mediaLauncher.launch(perm)
                                }
                            },
                        )
                        OmniDivider()
                        PermissionRow(
                            title = "Notifications",
                            subtitle = "Keep the agent loop alive as foreground service.",
                            granted = perms.notifications,
                            onOpen = {
                                Log.i(TAG, "Requesting Notifications permission")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAppSettings(ctx)
                                }
                            },
                        )
                    }
                }
                SettingsTab.SYSTEM -> {
                    SettingsCard(title = "Appearance") {
                        ToggleRow(
                            title = "Dark mode",
                            subtitle = "Inverts the B&W palette (true black background).",
                            checked = ui.darkMode,
                        ) { v -> scope.launch { vm.setUi(ui.copy(darkMode = v)) } }
                        OmniDivider()
                        ToggleRow(
                            title = "Text-To-Speech (On-Device)",
                            subtitle = "Speak agent thoughts and responses aloud.",
                            checked = ui.ttsEnabled,
                        ) { v -> scope.launch { vm.setUi(ui.copy(ttsEnabled = v)) } }
                        OmniDivider()
                        ToggleRow(
                            title = "Halo (Dynamic Island)",
                            subtitle = "Floating pill that shows live agent status.",
                            checked = haloEnabled,
                        ) { v ->
                            haloEnabled = v
                            if (v) com.omniclaw.app.service.HaloOverlayService.start(ctx)
                            else com.omniclaw.app.service.HaloOverlayService.stop(ctx)
                        }
                        OmniDivider()
                        ToggleRow(
                            title = "Monospace labels",
                            subtitle = "Use mono font for telemetry, IDs, status.",
                            checked = ui.monoFont,
                        ) { v -> scope.launch { vm.setUi(ui.copy(monoFont = v)) } }
                        OmniDivider()
                        ToggleRow(
                            title = "Show tool calls",
                            subtitle = "Display dispatched device actions in the chat.",
                            checked = ui.showToolCalls,
                        ) { v -> scope.launch { vm.setUi(ui.copy(showToolCalls = v)) } }
                        OmniDivider()
                        ToggleRow(
                            title = "Show thoughts",
                            subtitle = "Display the agent's internal reasoning lines.",
                            checked = ui.showThoughts,
                        ) { v -> scope.launch { vm.setUi(ui.copy(showThoughts = v)) } }
                        OmniDivider()
                        ToggleRow(
                            title = "Show token usage",
                            subtitle = "Display LLM token counts on each step.",
                            checked = ui.showTokens,
                        ) { v -> scope.launch { vm.setUi(ui.copy(showTokens = v)) } }
                    }

                    SettingsCard(title = "Backup & Restore") {
                        ConfigFileRow(
                            onExport = {
                                scope.launch {
                                    val file = vm.exportConfig()
                                    Toast.makeText(
                                        ctx,
                                        if (file != null) "Exported to ${file.absolutePath}"
                                        else "Export failed — check storage permission",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onImport = {
                                scope.launch {
                                    val ok = vm.importConfig()
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "Imported config from /sdcard/.xomniclaw/xomniclaw.json"
                                        else "Import failed — file not found or invalid",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        )
                    }

                    SettingsCard(title = "Privacy & Security") {
                        PrivacyDisclosureCard(
                            accepted = privacyAccepted ?: false,
                            onAccept = { scope.launch { vm.acceptPrivacy() } },
                        )
                        OmniDivider()
                        ApiKeyRotationCard(vm = vm)
                    }

                    SettingsCard(title = "About") {
                        AboutSection()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Privacy disclosure card — shown in Settings → System → Privacy & Security.
 *
 * Notifies the user that cloud LLM calls (OpenAI, Gemini, OpenRouter, etc.)
 * send prompts and agent decisions to external servers. Local/on-device models
 * (LiteRT) are excluded. The user must accept before the app will use cloud
 * providers — this satisfies basic privacy-compliance expectations.
 */
@Composable
private fun PrivacyDisclosureCard(accepted: Boolean, onAccept: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Cloud LLM Privacy Notice",
            fontFamily = OmniMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                accepted -> "✓ You have accepted the privacy notice. Cloud LLM calls send prompts to external servers. Local models (LiteRT) do not."
                else -> "This app can connect to cloud LLM providers (OpenAI, Gemini, OpenRouter, etc.). When enabled, your prompts and the agent's reasoning steps are sent to those servers for processing. Local/on-device models do NOT send data externally.\n\nDo you accept?"
            },
            fontFamily = OmniMono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!accepted) {
            Spacer(Modifier.height(12.dp))
            OmniButton(
                text = "I Understand — Accept",
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * API key rotation card — shows rotation status for each stored secret and
 * allows the user to manually trigger rotation (updates metadata, not the key value).
 */
@Composable
private fun ApiKeyRotationCard(vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadingKey by remember { mutableStateOf<String?>(null) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    val keys = listOf("agent", "gemini", "stt", "vlm")
    val keyLabels = mapOf(
        "agent" to "Agent API Key",
        "gemini" to "Gemini API Key",
        "stt" to "STT API Key",
        "vlm" to "VLM API Key",
    )

    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "API Key Rotation",
            fontFamily = OmniMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Rotate marks keys as recently rotated. Cached references should be refreshed. Actual key values are unchanged.",
            fontFamily = OmniMono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        keys.forEach { key ->
            RotationRow(
                label = keyLabels[key] ?: key,
                needsRotation = vm.needsRotation(key),
                lastRotationTime = vm.getLastRotationTime(key),
                onRotate = {
                    scope.launch {
                        loadingKey = key
                        lastMessage = null
                        val (success, message) = vm.rotateApiKey(key)
                        lastMessage = message
                        loadingKey = null
                        if (success) {
                            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                isLoading = loadingKey == key,
            )
            OmniDivider()
        }

        if (lastMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = lastMessage.orEmpty(),
                fontFamily = OmniMono,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RotationRow(
    label: String,
    needsRotation: Boolean,
    lastRotationTime: Long?,
    onRotate: () -> Unit,
    isLoading: Boolean,
) {
    val timeText = if (lastRotationTime != null) {
        val daysAgo = (System.currentTimeMillis() - lastRotationTime) / (24 * 3600 * 1000)
        "Last rotated: ${daysAgo}d ago"
    } else {
        "Never rotated"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontFamily = OmniMono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsRotation) {
                    Spacer(Modifier.width(6.dp))
                    OmniBadge("NEEDS ROTATION", filled = true)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = timeText,
                fontFamily = OmniMono,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRotate, enabled = !isLoading) {
            Icon(
                imageVector = Icons.Filled.Autorenew,
                contentDescription = "Rotate $label",
                tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ModelConfigEditor(
    cfg: ModelConfig, 
    onChange: (ModelConfig) -> Unit, 
    onProviderChanged: (com.omniclaw.app.data.prefs.LlmProvider, String, String) -> Unit,
    onDirty: (Boolean) -> Unit = {}
) {
    var localBaseUrl by rememberSaveable { mutableStateOf(cfg.baseUrl) }
    var localApiKey by rememberSaveable { mutableStateOf(cfg.apiKey) }
    var localModel by rememberSaveable { mutableStateOf(cfg.model) }
    var localTemp by rememberSaveable { mutableStateOf(cfg.temperature.toString()) }
    var localMaxTokens by rememberSaveable { mutableStateOf(cfg.maxTokens.toString()) }
    var localSttBaseUrl by rememberSaveable { mutableStateOf(cfg.sttBaseUrl) }
    var localSttApiKey by rememberSaveable { mutableStateOf(cfg.sttApiKey) }
    var localSttModel by rememberSaveable { mutableStateOf(cfg.sttModel) }
    var localVlmBaseUrl by rememberSaveable { mutableStateOf(cfg.vlmBaseUrl) }
    var localVlmApiKey by rememberSaveable { mutableStateOf(cfg.vlmApiKey) }
    var localVlmModel by rememberSaveable { mutableStateOf(cfg.vlmModel) }

    // State to toggle custom model text input visibility
    var showCustomModelInput by remember { mutableStateOf(false) }

    // H-35 FIX: tracks whether the user has unsaved local edits. While true,
    // the LaunchedEffect(cfg) sync below is skipped so an external config
    // emission (process-death restoration, preset/backup flow) doesn't clobber
    // typed-but-unsaved values. rememberSaveable so the guard itself survives
    // process death alongside the draft fields it protects.
    var userEdited by rememberSaveable { mutableStateOf(false) }

    // Synchronize local states when external config updates (presets, backups, etc.)
    LaunchedEffect(cfg) {
        // H-35 FIX: don't overwrite the user's unsaved edits.
        if (userEdited) return@LaunchedEffect
        localBaseUrl = cfg.baseUrl
        localApiKey = cfg.apiKey
        localModel = cfg.model
        localTemp = cfg.temperature.toString()
        localMaxTokens = cfg.maxTokens.toString()
        localSttBaseUrl = cfg.sttBaseUrl
        localSttApiKey = cfg.sttApiKey
        localSttModel = cfg.sttModel
        localVlmBaseUrl = cfg.vlmBaseUrl
        localVlmApiKey = cfg.vlmApiKey
        localVlmModel = cfg.vlmModel
    }

    val activeProviderId = getActiveProviderId(cfg)

    fun computeDirty(): Boolean = listOf(
        localBaseUrl.trim() != cfg.baseUrl.trim(),
        localApiKey != cfg.apiKey,
        localModel.trim() != cfg.model.trim(),
        localTemp.trim() != cfg.temperature.toString(),
        localMaxTokens.trim() != cfg.maxTokens.toString(),
        localSttBaseUrl.trim() != cfg.sttBaseUrl.trim(),
        localSttApiKey != cfg.sttApiKey,
        localSttModel.trim() != cfg.sttModel.trim(),
        localVlmBaseUrl.trim() != cfg.vlmBaseUrl.trim(),
        localVlmApiKey != cfg.vlmApiKey,
        localVlmModel.trim() != cfg.vlmModel.trim(),
    ).any { it }

    LaunchedEffect(
        localBaseUrl, localApiKey, localModel, localTemp, localMaxTokens,
        localSttBaseUrl, localSttApiKey, localSttModel,
        localVlmBaseUrl, localVlmApiKey, localVlmModel,
    ) {
        val dirty = computeDirty()
        // H-35 FIX: remember that the user has unsaved edits so a later cfg
        // emission doesn't reset the fields out from under them.
        userEdited = dirty
        onDirty(dirty)
    }

    Column(Modifier.padding(16.dp)) {
        // Merged Provider Selector Dropdown
        ProviderSelectDropdown(
            cfg = cfg,
            onProviderSelected = { provider, baseUrl, exampleModel ->
                showCustomModelInput = false
                onProviderChanged(provider, baseUrl, exampleModel)
            }
        )
        Spacer(Modifier.height(12.dp))

        // Dynamic Model Selection Dropdown
        ModelSelectDropdown(
            providerId = activeProviderId,
            selectedModel = localModel,
            onModelSelected = { model ->
                if (model == "custom_model_input") {
                    showCustomModelInput = true
                    localModel = ""
                } else {
                    showCustomModelInput = false
                    localModel = model
                }
            }
        )

        val commonModels = ProviderModels[activeProviderId] ?: emptyList()
        val isCustomModel = localModel.isNotBlank() && !commonModels.contains(localModel)
        
        if (showCustomModelInput || isCustomModel) {
            Spacer(Modifier.height(8.dp))
            LabeledTextField(
                label = "CUSTOM MODEL NAME",
                value = localModel,
                onValueChange = { localModel = it },
                placeholder = "Enter model name",
            )
        }
        Spacer(Modifier.height(8.dp))

        when (cfg.provider) {
            com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT -> {
                // Show Base URL for Custom or non-fixed OpenAI integrations
                if (activeProviderId == "custom" || activeProviderId == "openrouter" || activeProviderId == "ollama" || activeProviderId == "nvidia") {
                    LabeledTextField(
                        label = "BASE URL",
                        value = localBaseUrl,
                        onValueChange = { localBaseUrl = it },
                        placeholder = "https://api.openai.com/v1",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                
                // Show API key (unless Ollama which runs offline)
                if (activeProviderId != "ollama") {
                    var visible by remember { mutableStateOf(false) }
                    LabeledTextField(
                        label = "API KEY",
                        value = localApiKey,
                        onValueChange = { localApiKey = it },
                        placeholder = "sk-...",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        }
                    )
                }
            }
            com.omniclaw.app.data.prefs.LlmProvider.GEMINI -> {
                Text(
                    "BASE URL",
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "https://generativelanguage.googleapis.com/v1beta (fixed)",
                    fontFamily = OmniMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                var gemKeyVisible by remember { mutableStateOf(false) }
                LabeledTextField(
                    label = "GEMINI API KEY",
                    value = localApiKey,
                    onValueChange = { localApiKey = it },
                    placeholder = "AIza... (from aistudio.google.com/apikey)",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (gemKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { gemKeyVisible = !gemKeyVisible }) {
                            Icon(
                                if (gemKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle visibility"
                            )
                        }
                    }
                )
            }
            com.omniclaw.app.data.prefs.LlmProvider.LITERT -> {
                Text(
                    "ON-DEVICE INFERENCE",
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Runs locally — no internet or API key needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsAccordion(title = "Advanced Parameters") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledTextField(
                    label = "TEMPERATURE",
                    value = localTemp,
                    onValueChange = { v -> localTemp = v.filter { it.isDigit() || it == '.' } },
                    placeholder = "0.2",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                LabeledTextField(
                    label = "MAX TOKENS",
                    value = localMaxTokens,
                    onValueChange = { v -> localMaxTokens = v.filter { it.isDigit() } },
                    placeholder = "2048",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsAccordion(title = "Speech-to-Text (STT)") {
            LabeledTextField(
                label = "STT BASE URL",
                value = localSttBaseUrl,
                onValueChange = { localSttBaseUrl = it },
                placeholder = "https://api.siliconflow.cn/v1/audio/transcriptions",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(8.dp))
            var sttKeyVisible by remember { mutableStateOf(false) }
            LabeledTextField(
                label = "STT API KEY",
                value = localSttApiKey,
                onValueChange = { localSttApiKey = it },
                placeholder = "sk-xxx",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (sttKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { sttKeyVisible = !sttKeyVisible }) {
                        Icon(
                            if (sttKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            LabeledTextField(
                label = "STT MODEL",
                value = localSttModel,
                onValueChange = { localSttModel = it },
                placeholder = "FunAudioLLM/SenseVoiceSmall",
            )
        }

        Spacer(Modifier.height(8.dp))

        SettingsAccordion(title = "Vision LLM (VLM)") {
            LabeledTextField(
                label = "VLM BASE URL",
                value = localVlmBaseUrl,
                onValueChange = { localVlmBaseUrl = it },
                placeholder = "https://openrouter.ai/api/v1",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(8.dp))
            var vlmKeyVisible by remember { mutableStateOf(false) }
            LabeledTextField(
                label = "VLM API KEY",
                value = localVlmApiKey,
                onValueChange = { localVlmApiKey = it },
                placeholder = "sk-xxx",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (vlmKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { vlmKeyVisible = !vlmKeyVisible }) {
                        Icon(
                            if (vlmKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            LabeledTextField(
                label = "VLM MODEL",
                value = localVlmModel,
                onValueChange = { localVlmModel = it },
                placeholder = "qwen/qwen3.6-flash",
            )
        }

        Spacer(Modifier.height(20.dp))

        val dirty = computeDirty()
        OmniButton(
            text = if (dirty) "SAVE CONFIG" else "✓ SAVED",
            onClick = {
                val parsedTemp = localTemp.trim().toFloatOrNull() ?: cfg.temperature
                val parsedMaxTokens = localMaxTokens.trim().toIntOrNull() ?: cfg.maxTokens
                onChange(cfg.copy(
                    baseUrl = localBaseUrl.trim(),
                    apiKey = localApiKey,
                    model = localModel.trim(),
                    temperature = parsedTemp,
                    maxTokens = parsedMaxTokens,
                    sttBaseUrl = localSttBaseUrl.trim(),
                    sttApiKey = localSttApiKey,
                    sttModel = localSttModel.trim(),
                    vlmBaseUrl = localVlmBaseUrl.trim(),
                    vlmApiKey = localVlmApiKey,
                    vlmModel = localVlmModel.trim(),
                ))
                onDirty(false)
            },
            primary = dirty,
            enabled = dirty,
            leadingIcon = if (dirty) Icons.Outlined.Save else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChannelConfigEditor(
    cfg: ChannelConfig,
    onChange: (ChannelConfig) -> Unit,
    onDirty: (Boolean) -> Unit = {},
    onTestSend: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var testStatus by remember { mutableStateOf<String?>(null) }

    var localDiscordWebhook by rememberSaveable { mutableStateOf(cfg.discordWebhook) }

    var discordEnabled by remember { mutableStateOf(cfg.discordWebhook.isNotEmpty()) }

    // Synchronize local states when external config updates
    LaunchedEffect(cfg) {
        localDiscordWebhook = cfg.discordWebhook
        discordEnabled = cfg.discordWebhook.isNotEmpty()
    }

    fun computeDirty(): Boolean = listOf(
        localDiscordWebhook.trim() != cfg.discordWebhook.trim(),
    ).any { it }

    LaunchedEffect(
        localDiscordWebhook,
    ) {
        onDirty(computeDirty())
    }

    Column(Modifier.padding(16.dp)) {

        ToggleRow(
            title = "Discord Notifications",
            subtitle = "Send notifications to a Discord channel via webhook.",
            checked = discordEnabled,
            onToggle = { enabled ->
                discordEnabled = enabled
                if (!enabled) {
                    localDiscordWebhook = ""
                }
            }
        )

        if (discordEnabled) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                LabeledTextField(
                    label = "DISCORD WEBHOOK",
                    value = localDiscordWebhook,
                    onValueChange = { localDiscordWebhook = it },
                    placeholder = "https://discord.com/api/webhooks/xxx/xxx",
                    keyboardType = KeyboardType.Uri,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (discordEnabled) {
            Spacer(Modifier.height(8.dp))
            OmniButton(
                text = "TEST SEND",
                onClick = {
                    scope.launch {
                        testStatus = "Sending…"
                        val ok = onTestSend()
                        testStatus = if (ok) "Sent — check your channel" else "Send failed (check webhook config)"
                    }
                },
                primary = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (testStatus != null) {
                Text(
                    testStatus.orEmpty(),
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        val channelDirtyState = computeDirty()
        OmniButton(
            text = if (channelDirtyState) "SAVE CHANNELS" else "✓ SAVED",
            onClick = {
                onChange(cfg.copy(
                    discordWebhook = localDiscordWebhook.trim(),
                ))
                onDirty(false)
            },
            primary = channelDirtyState,
            enabled = channelDirtyState,
            leadingIcon = if (channelDirtyState) Icons.Outlined.Save else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val underlineColor = if (focused) MaterialTheme.colorScheme.onBackground
                         else MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Text(
            label,
            fontFamily = OmniMono,
            fontSize = 10.sp,
            color = if (focused) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = TextStyle(
                    fontFamily = OmniMono,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = visualTransformation,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                fontFamily = OmniMono,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                }
            )
            if (trailing != null) trailing()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 1.dp else 0.5.dp)
                .background(underlineColor)
        )
    }
}

@Composable
private fun ProviderSelectDropdown(
    cfg: ModelConfig,
    onProviderSelected: (com.omniclaw.app.data.prefs.LlmProvider, String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeId = getActiveProviderId(cfg)
    val activeLabel = when (activeId) {
        "gemini" -> "Google Gemini"
        "litert" -> "LiteRT (local)"
        "custom" -> "Custom (OpenAI-Compatible)"
        else -> ProviderPresets.find { it.id == activeId }?.label ?: "Custom"
    }

    Column {
        Text(
            "PROVIDER",
            fontFamily = OmniMono,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeLabel,
                        fontFamily = OmniMono,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
                shape = RoundedCornerShape(0.dp)
            ) {
                // List presets (which represent specific providers)
                ProviderPresets.forEach { preset ->
                    val presetProvider = when (preset.id) {
                        "gemini" -> com.omniclaw.app.data.prefs.LlmProvider.GEMINI
                        "litert" -> com.omniclaw.app.data.prefs.LlmProvider.LITERT
                        else -> com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT
                    }
                    val isSelected = activeId == preset.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = preset.label,
                                fontFamily = OmniMono,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.background
                                        else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = {
                            onProviderSelected(presetProvider, preset.baseUrl, preset.exampleModel)
                            expanded = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.surface
                            )
                    )
                }
                
                // Custom option
                val isCustomSelected = activeId == "custom"
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Custom (OpenAI-Compatible)",
                            fontFamily = OmniMono,
                            fontSize = 13.sp,
                            fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCustomSelected) MaterialTheme.colorScheme.background
                                    else MaterialTheme.colorScheme.onBackground
                        )
                    },
                    onClick = {
                        onProviderSelected(
                            com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT,
                            "",
                            ""
                        )
                        expanded = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCustomSelected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.surface
                        )
                )
            }
        }
    }
}

@Composable
private fun ModelSelectDropdown(
    providerId: String,
    selectedModel: String,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val commonModels = ProviderModels[providerId] ?: emptyList()
    
    val isCustomModel = selectedModel.isNotBlank() && !commonModels.contains(selectedModel)
    val isModelSelectedCustom = selectedModel == "custom_model_input" || isCustomModel
    
    val displayValue = if (isCustomModel) selectedModel else if (selectedModel.isBlank()) "Select a Model" else selectedModel

    Column {
        Text(
            "MODEL",
            fontFamily = OmniMono,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayValue,
                        fontFamily = OmniMono,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
                shape = RoundedCornerShape(0.dp)
            ) {
                commonModels.forEach { model ->
                    val isSelected = selectedModel == model
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                fontFamily = OmniMono,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.background
                                        else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.surface
                            )
                    )
                }
                
                if (providerId != "litert") {
                    val isSelected = isModelSelectedCustom
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Custom...",
                                fontFamily = OmniMono,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.background
                                        else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = {
                            onModelSelected("custom_model_input")
                            expanded = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.surface
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAccordion(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    fontFamily = OmniMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
            ) {
                Column(Modifier.padding(16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ConfigFileRow(onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(
            "Export or import your configuration as a JSON file for backup or migration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "API keys are masked on export — re-enter them after import.",
            fontFamily = OmniMono,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OmniButton(
                text = "EXPORT",
                onClick = onExport,
                primary = false,
                leadingIcon = Icons.Outlined.Download,
                modifier = Modifier.weight(1f),
            )
            OmniButton(
                text = "IMPORT",
                onClick = onImport,
                primary = false,
                leadingIcon = Icons.Outlined.Upload,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    OmniRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            OmniBadge(if (granted) "GRANTED" else "DENIED", filled = granted)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Open")
            }
        },
        onClick = onOpen,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.onBackground,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                checkedBorderColor = MaterialTheme.colorScheme.onBackground,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun AboutSection() {
    Column(Modifier.padding(16.dp)) {
        Text(
            "X-OmniClaw",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Edge-native multimodal Android agent with self-learning.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Version 1.1.0 · Android 8.0+",
            fontFamily = OmniMono,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openAppSettings(ctx: Context) {
    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", ctx.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(i) }
}

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            title.uppercase(),
            fontFamily = OmniMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}
