package com.omniclaw.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.data.model.ChatMessage
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniEmptyState
import com.omniclaw.app.ui.components.OmniStat
import com.omniclaw.app.ui.components.OmniTopBar
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.omniclaw.app.data.prefs.UiPrefs
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(sessionId: String? = null) {
    val vm: ChatViewModel = hiltViewModel()
    val session by vm.activeSession.collectAsStateWithLifecycle()
    val ui by vm.uiPrefs.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            vm.open(sessionId)
        }
    }

    // Scroll when new messages are added or status changes
    LaunchedEffect(session?.messages?.size, session?.status) {
        val n = session?.messages?.size ?: 0
        if (n > 0) {
            val isRunning = session?.status == SessionStatus.RUNNING
            val targetIndex = n - 1 + (if (isRunning) 1 else 0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    // Snappily adjust scroll position when screen height changes (e.g. keyboard opens)
    // without running an ongoing scroll animation that causes text field focus loss.
    val listHeight = listState.layoutInfo.viewportSize.height
    LaunchedEffect(listHeight) {
        val n = session?.messages?.size ?: 0
        if (n > 0) {
            val isRunning = session?.status == SessionStatus.RUNNING
            val targetIndex = n - 1 + (if (isRunning) 1 else 0)
            listState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        OmniTopBar(
            title = "AGENT",
            subtitle = (session?.title ?: "No active session — type to start.").uppercase()
        )
        OmniDivider()

        // Compact status bar: steps + tokens + LIVE badge with pulse
        if (session != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STEPS: ${session!!.stepCount}",
                    fontFamily = OmniMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (ui.showTokens) {
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(1.dp, 12.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "TOKENS: ${session!!.tokenUsage}",
                        fontFamily = OmniMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.weight(1f))
                if (session!!.status == SessionStatus.RUNNING) {
                    OmniBadge("LIVE", filled = true, pulsing = true)
                }
            }
            OmniDivider()
        }

        // Messages list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val filteredMessages = session?.messages.orEmpty().filter { m ->
                when (m.role) {
                    ChatMessage.Role.TOOL -> ui.showToolCalls
                    else -> true
                }
            }
            items(filteredMessages, key = { it.id }) { m ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(Motion.DurationMedium)),
                ) {
                    MessageRow(m, ui)
                }
            }
            if (session == null) {
                item { EmptyChatPlaceholder() }
            } else if (session!!.status == SessionStatus.RUNNING && session!!.messages.isNotEmpty()) {
                item { TypingIndicator() }
            }
        }

        OmniDivider()
        Composer(
            running = session?.status == SessionStatus.RUNNING,
            onSend = { text -> vm.send(text) },
            onStop = { vm.stop() },
        )
    }
}

@Composable
private fun MessageRow(m: ChatMessage, ui: UiPrefs) {
    when (m.role) {
        ChatMessage.Role.USER -> UserBubble(m)
        ChatMessage.Role.ASSISTANT -> AssistantBlock(m, ui)
        ChatMessage.Role.TOOL -> ToolBlock(m.toolCalls)
        ChatMessage.Role.SYSTEM -> SystemNote(m.content)
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(0.dp),
            ) {
                Text(
                    text = m.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontFamily = OmniMono,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                timeFmt.format(Date(m.timestamp)),
                fontFamily = OmniMono,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantBlock(m: ChatMessage, ui: UiPrefs) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AGENT",
                fontFamily = OmniMono,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                timeFmt.format(Date(m.timestamp)),
                fontFamily = OmniMono,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        // Strip the THOUGHT: / ACTION: scaffolding if showThoughts is false,
        // otherwise show the full raw thought content for debugging/transparency.
        val displayText = remember(m.content, ui.showThoughts) {
            if (ui.showThoughts) m.content else cleanThoughtForDisplay(m.content)
        }
        Text(
            displayText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Convert a raw agent thought (which contains "THOUGHT: ...\nACTION: ..." lines)
 * into a clean user-facing string. If the thought has a THOUGHT: line, we show
 * just that line's content (the conversational reply). If it has no THOUGHT:
 * prefix at all (raw text), we show it as-is. ACTION: lines are never shown
 * to the user — they're rendered separately as ToolBlocks when the action is
 * a device action.
 */
private fun cleanThoughtForDisplay(raw: String): String {
    val thoughtLine = Regex("(?mi)^thought:\\s*(.+)$").find(raw)?.groupValues?.getOrNull(1)?.trim()
    return thoughtLine?.takeIf { it.isNotBlank() } ?: raw.trim()
}

@Composable
private fun ToolBlock(calls: List<com.omniclaw.app.data.model.ToolCall>) {
    if (calls.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                    contentDescription = "Toggle",
                    tint = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "DISPATCHED ACTIONS (${calls.size})",
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                val successCount = calls.count { it.ok }
                OmniBadge(
                    text = if (successCount == calls.size) "ALL OK" else "$successCount/${calls.size} OK",
                    filled = successCount == calls.size
                )
            }
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
                    .padding(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    calls.forEach { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OmniBadge(if (c.ok) "OK" else "ERR", filled = !c.ok)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                c.name,
                                fontFamily = OmniMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${c.durationMs}ms",
                                fontFamily = OmniMono,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!c.result.isNullOrBlank() && c.result != "ok") {
                            Text(
                                "→ ${c.result}",
                                fontFamily = OmniMono,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Text(
        text = "— $text —",
        fontFamily = OmniMono,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Three-dot typing indicator — pulses in sequence while the agent is running.
 */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400), repeatMode = RepeatMode.Reverse),
        label = "dot1",
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 150), repeatMode = RepeatMode.Reverse),
        label = "dot2",
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 300), repeatMode = RepeatMode.Reverse),
        label = "dot3",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).alpha(alpha1).background(MaterialTheme.colorScheme.onBackground))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(6.dp).alpha(alpha2).background(MaterialTheme.colorScheme.onBackground))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(6.dp).alpha(alpha3).background(MaterialTheme.colorScheme.onBackground))
        Spacer(Modifier.width(10.dp))
        Text(
            "thinking",
            fontFamily = OmniMono,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    OmniEmptyState(
        title = "Ask anything",
        subtitle = "Type a question or a command.\n\nExamples:\n• What's the weather like today?\n• Explain how transformers work\n• Search Reddit for budget travel tips\n• On Amazon, find men's light sunscreen shirts",
    )
}

@Composable
private fun Composer(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    // rememberSaveable so the typed-but-unsent text survives rotation and
    // process death. Previously `remember` was used, which lost the draft
    // on any configuration change.
    var text by rememberSaveable { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var overlayOn by rememberSaveable { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = {
                // Toggle the floating push-to-talk bubble. Press-and-hold the bubble
                // to record; release to transcribe and dispatch to the agent loop.
                overlayOn = !overlayOn
                if (overlayOn) com.omniclaw.app.service.OverlayService.start(ctx)
                else com.omniclaw.app.service.OverlayService.stop(ctx)
            }) {
                Box(
                    modifier = Modifier
                        .size(48.dp),  // 48dp min touch target
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = if (overlayOn) "Hide bubble" else "Show bubble",
                        tint = if (overlayOn) MaterialTheme.colorScheme.background
                               else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(
                            if (overlayOn) MaterialTheme.colorScheme.onBackground
                            else androidx.compose.ui.graphics.Color.Transparent
                        ).padding(6.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        if (running) "Agent is running…" else "Message the agent",
                        fontFamily = OmniMono,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        fontFamily = OmniMono,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (running) ImeAction.None else ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() && !running) {
                                keyboardController?.hide()
                                onSend(text.trim())
                                text = ""
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (running) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Stop",
                        modifier = Modifier.size(28.dp))
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            keyboardController?.hide()
                            onSend(text.trim())
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (text.isNotBlank()) MaterialTheme.colorScheme.onBackground
                                else androidx.compose.ui.graphics.Color.Transparent
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.background
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
