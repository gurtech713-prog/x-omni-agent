package com.omniclaw.app.ui.sessions

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.core.theme.pulseAlpha
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniButton
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniEmptyState
import com.omniclaw.app.ui.components.OmniRow
import com.omniclaw.app.ui.components.OmniSectionHeader
import com.omniclaw.app.ui.components.OmniTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SessionsScreen"

private enum class SessionsTab(val label: String) {
    ACTIVE_SESSIONS("Sessions"),
    BEHAVIORS("Behaviors")
}

@Composable
fun SessionsScreen(onSelectSession: (String) -> Unit) {
    val vm: SessionsViewModel = hiltViewModel()
    val list by vm.sessions.collectAsStateWithLifecycle()
    val recording by vm.isRecording.collectAsStateWithLifecycle()
    val savedBehaviors by vm.savedBehaviors.collectAsStateWithLifecycle()
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var saveTrigger by rememberSaveable { mutableStateOf("") }
    // U-M8: rememberSaveable so the open delete-confirmation dialog survives
    // configuration changes / process death — a rotation no longer silently
    // dismisses the prompt and lets the user accidentally tap DELETE on the
    // row underneath.
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeTab by rememberSaveable { mutableStateOf(SessionsTab.ACTIVE_SESSIONS) }

    // U-H3: per-screen BackHandler that consumes back when a dialog is open
    // and dismisses it, preventing the OmniApp-level BackHandler from
    // navigating to Chat out from under the user. The AlertDialog's own
    // onDismissRequest handles the same case when the dialog window itself
    // has focus, but the OmniApp BackHandler (registered on the Activity's
    // dispatcher) can fire first on some Compose versions / device themes.
    val anyDialogOpen = pendingDeleteId != null || showSaveDialog
    BackHandler(enabled = anyDialogOpen) {
        if (pendingDeleteId != null) {
            Log.d(TAG, "Back press consumed: dismissing delete-session dialog")
            pendingDeleteId = null
        } else if (showSaveDialog) {
            Log.d(TAG, "Back press consumed: dismissing save-behavior dialog")
            showSaveDialog = false
            saveName = ""
            saveTrigger = ""
        }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "SessionsScreen composed")
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OmniTopBar(
            title = "Sessions",
            subtitle = "${list.size} total · ${list.count { it.status == SessionStatus.RUNNING }} running",
            actions = {
                OmniButton(
                    text = "NEW",
                    onClick = { 
                        Log.i(TAG, "New Session button clicked")
                        val newId = vm.newSession()
                        onSelectSession(newId)
                    },
                    leadingIcon = Icons.Outlined.Add,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        )
        OmniDivider()

        // Tab Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SessionsTab.entries.forEach { tab ->
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

        when (activeTab) {
            SessionsTab.ACTIVE_SESSIONS -> {
                if (list.isEmpty()) {
                    OmniEmptyState(
                        title = "No sessions yet",
                        subtitle = "Send a message in Chat or tap NEW SESSION to start.",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(list, key = { it.id }) { s ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(Motion.DurationMedium))
                            ) {
                                SessionRow(
                                    session = s,
                                    onStop = { vm.stop(s.id) },
                                    onDelete = { pendingDeleteId = s.id },
                                    onClick = { onSelectSession(s.id) },
                                )
                            }
                            OmniDivider()
                        }
                    }
                }
            }
            SessionsTab.BEHAVIORS -> {
                // Behavior Cloning controls
                OmniSectionHeader("Record automation")
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        if (recording) 1.dp else 0.5.dp,
                        if (recording) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (recording) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .pulseAlpha(0.3f)
                                    .background(MaterialTheme.colorScheme.onBackground)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "RECORDING",
                                fontFamily = OmniMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            OmniButton(
                                text = "STOP & SAVE",
                                onClick = {
                                    Log.i(TAG, "Stop & Save button clicked for behavior recording")
                                    showSaveDialog = true
                                },
                                leadingIcon = Icons.Outlined.Stop
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = {
                                Log.i(TAG, "Cancel behavior recording button clicked")
                                vm.cancelRecording()
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                            }
                        } else {
                            Text(
                                "Record a flow once, replay it later by name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            OmniButton(
                                text = "RECORD",
                                onClick = {
                                    Log.i(TAG, "Start recording button clicked")
                                    vm.startRecording()
                                },
                                leadingIcon = Icons.Outlined.FiberManualRecord
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OmniDivider()

                if (savedBehaviors.isEmpty()) {
                    OmniEmptyState(
                        title = "No saved behaviors",
                        subtitle = "Recorded agent actions will appear here for one-click replay.",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item { OmniSectionHeader("Saved behavior skills") }
                        items(savedBehaviors, key = { it.id }) { skill ->
                            OmniRow(
                                title = skill.name,
                                subtitle = "${skill.actions.size} actions · \"${skill.triggerPhrase}\"",
                                leading = {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.onBackground)
                                    )
                                },
                                trailing = {
                                    IconButton(onClick = {
                                        Log.i(TAG, "Replay button clicked for behavior skill: ${skill.id} (${skill.name})")
                                        vm.replay(skill.id)
                                    }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Replay")
                                    }
                                },
                                onClick = null,
                            )
                            OmniDivider()
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = {
                Log.d(TAG, "Delete session dialog dismissed")
                pendingDeleteId = null
            },
            shape = RoundedCornerShape(0.dp),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            title = {
                Text(
                    "Delete session?",
                    fontFamily = OmniMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Text(
                    "This will permanently remove the session and its message history. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                OmniButton(
                    text = "DELETE",
                    onClick = {
                        Log.i(TAG, "Delete session confirmed for: $id")
                        vm.delete(id)
                        pendingDeleteId = null
                    },
                    primary = true
                )
            },
            dismissButton = {
                OmniButton(
                    text = "CANCEL",
                    onClick = {
                        Log.d(TAG, "Delete session cancelled")
                        pendingDeleteId = null
                    },
                    primary = false
                )
            },
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                Log.d(TAG, "Save behavior skill dialog dismissed")
                showSaveDialog = false
                saveName = ""
                saveTrigger = ""
            },
            shape = RoundedCornerShape(0.dp),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            title = {
                Text(
                    "Save behavior skill",
                    fontFamily = OmniMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Skill name", fontFamily = OmniMono, fontSize = 10.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveTrigger,
                        onValueChange = { saveTrigger = it },
                        label = { Text("Trigger phrase (e.g. \"Open Amazon quick link\")", fontFamily = OmniMono, fontSize = 10.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                OmniButton(
                    text = "SAVE",
                    onClick = {
                        val savedName = saveName.ifBlank { "Untitled" }
                        val savedTrigger = saveTrigger.ifBlank { "Open $savedName" }
                        Log.i(TAG, "Save behavior skill confirmed: name='$savedName', trigger='$savedTrigger'")
                        vm.stopAndSaveRecording(savedName, savedTrigger)
                        showSaveDialog = false
                        saveName = ""
                        saveTrigger = ""
                    },
                    primary = true
                )
            },
            dismissButton = {
                OmniButton(
                    text = "CANCEL",
                    onClick = {
                        Log.d(TAG, "Save behavior skill cancelled")
                        showSaveDialog = false
                        saveName = ""
                        saveTrigger = ""
                    },
                    primary = false
                )
            },
        )
    }
}

@Composable
private fun SessionRow(session: Session, onStop: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    OmniRow(
        title = session.title,
        subtitle = "${df.format(Date(session.createdAt))} · ${session.stepCount} steps · ${session.tokenUsage} tokens",
        leading = {
            // Status dot — pulsing for running, hollow ring for failed, filled for others
            when (session.status) {
                SessionStatus.RUNNING -> Box(
                    Modifier
                        .pulseAlpha(0.3f)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.onBackground)
                )
                SessionStatus.FAILED -> Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.onBackground)
                )
                else -> Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.onBackground)
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(session.status)
                Spacer(Modifier.width(8.dp))
                if (session.status == SessionStatus.RUNNING) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Outlined.Stop, contentDescription = "Stop")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Close, contentDescription = "Delete")
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun StatusBadge(s: SessionStatus) {
    when (s) {
        SessionStatus.RUNNING -> OmniBadge("LIVE", filled = true, pulsing = true)
        SessionStatus.IDLE -> OmniBadge("IDLE", filled = false)
        SessionStatus.STOPPED -> OmniBadge("STOP", filled = false)
        SessionStatus.FAILED -> OmniBadge("ERR", filled = true)
        SessionStatus.DONE -> OmniBadge("DONE", filled = false)
    }
}
