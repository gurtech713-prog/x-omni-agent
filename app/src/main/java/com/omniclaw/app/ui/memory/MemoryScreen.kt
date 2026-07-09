package com.omniclaw.app.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.data.model.MemoryEntry
import com.omniclaw.app.data.model.MemoryEntry.MemoryKind
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniButton
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniEmptyState
import com.omniclaw.app.ui.components.OmniSectionHeader
import com.omniclaw.app.ui.components.OmniTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MemoryTab(val label: String) {
    MEMORIES("Memories"),
    LESSONS("Lessons"),
    SKILLS("Skills")
}

@Composable
fun MemoryScreen() {
    val vm: MemoryViewModel = hiltViewModel()
    val lessonsVm: LessonsViewModel = hiltViewModel()
    val list by vm.entries.collectAsStateWithLifecycle()
    val lessons by lessonsVm.lessons.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }
    var activeTab by rememberSaveable { mutableStateOf(MemoryTab.MEMORIES) }
    var selectedKindFilter by rememberSaveable { mutableStateOf<MemoryKind?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OmniTopBar(
            title = "MEMORY & SKILLS",
            subtitle = "${list.size} entries · ${list.count { it.pinned }} pinned · ${lessons.size} lessons · ${skills.size} skills"
        )
        OmniDivider()

        // Tab Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MemoryTab.values().forEach { tab ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeTab = tab }
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
            MemoryTab.MEMORIES -> {
                // Category Filter & Clear Action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        MemoryFilterDropdown(
                            selectedKind = selectedKindFilter,
                            onSelect = { selectedKindFilter = it }
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OmniButton(
                        text = "CLEAR WORKING",
                        onClick = { pendingClear = ClearTarget.Working },
                        primary = false
                    )
                }
                OmniDivider()

                val filteredEntries = if (selectedKindFilter != null) {
                    list.filter { it.kind == selectedKindFilter }
                } else {
                    list
                }
                val grouped = filteredEntries.groupBy { it.kind }

                if (filteredEntries.isEmpty()) {
                    OmniEmptyState(
                        title = "No memories found",
                        subtitle = "Memory entries appear here after running tasks.",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MemoryKind.entries.forEach { kind ->
                            val items = grouped[kind]
                            if (items.isNullOrEmpty()) return@forEach
                            item { OmniSectionHeader(title = kindLabel(kind)) }
                            items(items, key = { it.id }) { m ->
                                AnimatedVisibility(visible = true, enter = fadeIn(tween(Motion.DurationMedium))) {
                                    MemoryRow(m, df.format(Date(m.createdAt))) { action ->
                                        when (action) {
                                            MemoryRowAction.Pin -> vm.pin(m.id, !m.pinned)
                                            MemoryRowAction.Forget -> vm.forget(m.id)
                                        }
                                    }
                                }
                                OmniDivider()
                            }
                        }
                    }
                }
            }
            MemoryTab.LESSONS -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmniButton(
                        text = "CLEAR LESSONS",
                        onClick = { pendingClear = ClearTarget.Lessons },
                        primary = false
                    )
                }
                OmniDivider()

                if (lessons.isEmpty()) {
                    OmniEmptyState(
                        title = "No lessons learned yet",
                        subtitle = "Accumulated successes and failures will log details here.",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(lessons, key = { it.id }) { l ->
                            AnimatedVisibility(visible = true, enter = fadeIn(tween(Motion.DurationMedium))) {
                                LessonRow(l, df.format(Date(l.lastSeenAt))) {
                                    lessonsVm.forget(l.id)
                                }
                            }
                            OmniDivider()
                        }
                    }
                }
            }
            MemoryTab.SKILLS -> {
                if (skills.isEmpty()) {
                    OmniEmptyState(
                        title = "No skills loaded",
                        subtitle = "Loaded agent action schemas will appear here.",
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item { OmniSectionHeader(title = "Skills (${skills.count { it.enabled }}/${skills.size} enabled)") }
                        items(skills, key = { it.id }) { skill ->
                            SkillRow(skill) { enabled -> vm.toggleSkill(skill.id, enabled) }
                            OmniDivider()
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog
    pendingClear?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            shape = RoundedCornerShape(0.dp),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            title = {
                Text(
                    "Clear ${target.label}?",
                    fontFamily = OmniMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Text(
                    target.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                OmniButton(
                    text = "CLEAR",
                    onClick = {
                        when (target) {
                            ClearTarget.Working -> vm.clearWorking()
                            ClearTarget.Lessons -> lessonsVm.clearAll()
                        }
                        pendingClear = null
                    },
                    primary = true
                )
            },
            dismissButton = {
                OmniButton(
                    text = "CANCEL",
                    onClick = { pendingClear = null },
                    primary = false
                )
            },
        )
    }
}

@Composable
private fun MemoryFilterDropdown(
    selectedKind: MemoryKind?,
    onSelect: (MemoryKind?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(null) + MemoryKind.entries
    val label = when (selectedKind) {
        null -> "All Categories"
        MemoryKind.WORKING -> "Working memory"
        MemoryKind.LONG_TERM -> "Long-term"
        MemoryKind.FACT -> "Facts"
        MemoryKind.PREFERENCE -> "Preferences"
        MemoryKind.EPISODE -> "Episodes"
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontFamily = OmniMono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                    contentDescription = "Expand Filter",
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
            options.forEach { opt ->
                val isSelected = selectedKind == opt
                val optLabel = when (opt) {
                    null -> "All Categories"
                    MemoryKind.WORKING -> "Working memory"
                    MemoryKind.LONG_TERM -> "Long-term memory"
                    MemoryKind.FACT -> "Facts"
                    MemoryKind.PREFERENCE -> "Preferences"
                    MemoryKind.EPISODE -> "Episodes"
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optLabel,
                            fontFamily = OmniMono,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.background
                                    else MaterialTheme.colorScheme.onBackground
                        )
                    },
                    onClick = {
                        onSelect(opt)
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

private enum class ClearTarget(val label: String, val message: String) {
    Working("working memory", "This will remove all working-memory entries. Long-term memories, facts, and pinned entries are kept. This cannot be undone."),
    Lessons("all lessons", "This will remove all learned lessons the agent has accumulated across sessions. This cannot be undone."),
}

private enum class MemoryRowAction { Pin, Forget }

@Composable
private fun LessonRow(
    lesson: com.omniclaw.app.data.local.LessonEntity,
    lastSeen: String,
    onForget: () -> Unit,
) {
    val outcomeLabel = when (lesson.outcome) {
        "FAILURE" -> "AVOID"
        "SUCCESS" -> "USE"
        "LOOP" -> "LOOP"
        else -> "NOTE"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val bars = lesson.confidence.coerceAtMost(5)
        Column(modifier = Modifier.padding(end = 14.dp, top = 2.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier
                        .size(width = 3.dp, height = 8.dp)
                        .padding(bottom = 1.dp)
                        .background(
                            if (i < bars) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniBadge(outcomeLabel, filled = lesson.outcome == "FAILURE")
                Spacer(Modifier.width(8.dp))
                Text(
                    "conf ${lesson.confidence}",
                    fontFamily = OmniMono,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                lesson.lessonText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                lastSeen,
                fontFamily = OmniMono,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onForget) {
            Icon(Icons.Outlined.Delete, contentDescription = "Forget lesson")
        }
    }
}

@Composable
private fun MemoryRow(m: MemoryEntry, ts: String, onAction: (MemoryRowAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(if (m.pinned) 10.dp else 8.dp)
                .background(
                    if (m.pinned) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = if (m.pinned) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniBadge(if (m.pinned) "PIN" else "MEM", filled = m.pinned)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$ts · source: ${m.source}",
                    fontFamily = OmniMono,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onAction(MemoryRowAction.Pin) }) {
            Icon(
                if (m.pinned) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "Pin"
            )
        }
        IconButton(onClick = { onAction(MemoryRowAction.Forget) }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Forget")
        }
    }
}

private fun kindLabel(k: MemoryKind): String = when (k) {
    MemoryKind.WORKING -> "Working memory"
    MemoryKind.LONG_TERM -> "Long-term memory"
    MemoryKind.FACT -> "Facts"
    MemoryKind.PREFERENCE -> "Preferences"
    MemoryKind.EPISODE -> "Episodes"
}

@Composable
private fun SkillRow(skill: Skill, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(8.dp).background(MaterialTheme.colorScheme.onBackground)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(8.dp))
                OmniBadge(skill.id, filled = false)
            }
            Spacer(Modifier.size(4.dp))
            Text(
                skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (skill.examples.isNotEmpty()) {
                skill.examples.firstOrNull()?.let { ex ->
                    Text(
                        "e.g. $ex",
                        fontFamily = OmniMono,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = skill.enabled,
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
