package com.omniclaw.app.ui.schedule

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
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
import com.omniclaw.app.data.model.ScheduledTask
import com.omniclaw.app.data.model.ScheduledTask.ScheduleKind
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniButton
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniEmptyState
import com.omniclaw.app.ui.components.OmniTopBar

@Composable
fun ScheduleScreen() {
    val vm: ScheduleViewModel = hiltViewModel()
    val list by vm.tasks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OmniTopBar(title = "SCHEDULE", subtitle = "${list.size} tasks · ${list.count { it.enabled }} enabled")
        OmniDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OmniButton(text = "NEW TASK", onClick = {
                editing = ScheduledTask(
                    id = java.util.UUID.randomUUID().toString().take(8),
                    title = "Untitled task",
                    scheduleKind = ScheduleKind.INTERVAL,
                    intervalMinutes = 30,
                    timeOfDay = "",
                    enabled = false,
                    prompt = "",
                )
            }, leadingIcon = Icons.Outlined.Add)
        }

        if (list.isEmpty()) {
            OmniEmptyState(
                title = "No scheduled tasks",
                subtitle = "Tap NEW TASK or ask the agent: skill:scheduled-automation(60|prompt)",
                modifier = Modifier.padding(top = 40.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(list, key = { it.id }) { t ->
                AnimatedVisibility(visible = true, enter = fadeIn(tween(Motion.DurationMedium))) {
                    ScheduleRow(
                        t,
                        onToggle = { vm.toggle(t.id) },
                        onDelete = { vm.delete(t.id) },
                        onEdit = { editing = t },
                    )
                }
                OmniDivider()
            }
        }
    }

    editing?.let { task ->
        EditTaskDialog(
            task = task,
            isNew = list.none { it.id == task.id },
            onSave = { updated ->
                if (list.none { it.id == updated.id }) vm.create(updated)
                else vm.update(updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ScheduleRow(
    t: ScheduledTask,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(8.dp).background(
                    if (t.enabled) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.outlineVariant
                )
            )
            Spacer(Modifier.width(14.dp))
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            OmniBadge(t.scheduleKind.name, filled = false)
            if (t.enabled) {
                Spacer(Modifier.width(6.dp))
                OmniBadge("ON", filled = true, pulsing = false)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = t.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.background,
                    checkedTrackColor = MaterialTheme.colorScheme.onBackground,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                    checkedBorderColor = MaterialTheme.colorScheme.onBackground,
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit task")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Close, contentDescription = "Delete task")
            }
        }
        
        if (expanded) {
            Spacer(Modifier.size(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp)
            ) {
                Text(
                    text = "FREQUENCY",
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    scheduleDescription(t),
                    fontFamily = OmniMono,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (t.prompt.isNotBlank()) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "PROMPT DIRECTIVE",
                        fontFamily = OmniMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "» ${t.prompt}",
                        fontFamily = OmniMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                Spacer(Modifier.size(10.dp))
                Text(
                    text = "RUN TELEMETRY",
                    fontFamily = OmniMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("runs: ${t.runCount}")
                        if (t.lastRunAt != null) {
                            val mins = (System.currentTimeMillis() - t.lastRunAt) / 60_000
                            append("  ·  last: ${if (mins < 60) "${mins}m" else "${mins / 60}h"} ago")
                        } else {
                            append("  ·  last: never")
                        }
                        if (t.nextRunAt != null) {
                            val mins = (t.nextRunAt - System.currentTimeMillis()) / 60_000
                            append("  ·  next: in ${if (mins < 60) "${mins}m" else "${mins / 60}h"}")
                        }
                    },
                    fontFamily = OmniMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun EditTaskDialog(
    task: ScheduledTask,
    isNew: Boolean,
    onSave: (ScheduledTask) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var prompt by rememberSaveable(task.id) { mutableStateOf(task.prompt) }
    var kindName by rememberSaveable(task.id) { mutableStateOf(task.scheduleKind.name) }
    var interval by rememberSaveable(task.id) { mutableStateOf((task.intervalMinutes ?: 30).toString()) }
    var timeOfDay by rememberSaveable(task.id) { mutableStateOf(task.timeOfDay.ifBlank { "09:00" }) }
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var weekdaysStr by rememberSaveable(task.id) {
        mutableStateOf(task.weekdays.sorted().joinToString(","))
    }
    val kind = runCatching { ScheduleKind.valueOf(kindName) }.getOrDefault(ScheduleKind.INTERVAL)
    val weekdays = remember(weekdaysStr) {
        weekdaysStr.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    var kindDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        title = {
            Text(
                text = if (isNew) "New scheduled task" else "Edit task",
                fontFamily = OmniMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", fontFamily = OmniMono, fontSize = 10.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(0.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt (sent to the agent)", fontFamily = OmniMono, fontSize = 10.sp) },
                    shape = RoundedCornerShape(0.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                
                // Dropdown for Schedule Type selection
                Column {
                    Text(
                        "Schedule Type",
                        fontFamily = OmniMono,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            onClick = { kindDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = kind.name,
                                    fontFamily = OmniMono,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (kindDropdownExpanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                                    contentDescription = "Expand",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = kindDropdownExpanded,
                            onDismissRequest = { kindDropdownExpanded = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
                            shape = RoundedCornerShape(0.dp)
                        ) {
                            ScheduleKind.entries.forEach { k ->
                                val isSelected = kind == k
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = k.name,
                                            fontFamily = OmniMono,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.background
                                                    else MaterialTheme.colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        kindName = k.name
                                        kindDropdownExpanded = false
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
                Spacer(Modifier.height(8.dp))
                
                if (kind == ScheduleKind.INTERVAL) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Interval (minutes)", fontFamily = OmniMono, fontSize = 10.sp) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = timeOfDay,
                        onValueChange = { timeOfDay = it },
                        label = { Text("Time of day (HH:mm)", fontFamily = OmniMono, fontSize = 10.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                
                if (kind == ScheduleKind.WEEKLY) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Days of week",
                        fontFamily = OmniMono,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        (1..7).forEach { dayNum ->
                            val selected = dayNum in weekdays
                            // Simple Custom filter button to match theme and fit row
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .weight(1f)
                                    .border(
                                        0.5.dp,
                                        if (selected) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        weekdaysStr = if (selected) {
                                            (weekdays - dayNum).sorted().joinToString(",")
                                        } else {
                                            (weekdays + dayNum).sorted().joinToString(",")
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayNames[dayNum - 1],
                                    fontFamily = OmniMono,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.background
                                            else MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = MaterialTheme.colorScheme.onBackground,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                            checkedBorderColor = MaterialTheme.colorScheme.onBackground,
                            uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Enabled", fontFamily = OmniMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        },
        confirmButton = {
            OmniButton(
                text = if (isNew) "CREATE" else "SAVE",
                onClick = {
                    val finalWeekdays = if (kind == ScheduleKind.WEEKLY && weekdays.isEmpty()) {
                        setOf(4)
                    } else weekdays
                    onSave(
                        task.copy(
                            title = title.ifBlank { "Untitled task" },
                            prompt = prompt,
                            scheduleKind = kind,
                            intervalMinutes = if (kind == ScheduleKind.INTERVAL) interval.toIntOrNull() ?: 30 else null,
                            timeOfDay = if (kind == ScheduleKind.INTERVAL) "" else timeOfDay,
                            weekdays = if (kind == ScheduleKind.WEEKLY) finalWeekdays else emptySet(),
                            enabled = enabled,
                        )
                    )
                },
                primary = true
            )
        },
        dismissButton = {
            OmniButton(
                text = "CANCEL",
                onClick = onDismiss,
                primary = false
            )
        },
    )
}

private fun scheduleDescription(t: ScheduledTask): String = when (t.scheduleKind) {
    ScheduleKind.INTERVAL -> "Every ${t.intervalMinutes ?: 60} minutes (works screen-on or screen-off)"
    ScheduleKind.WEEKDAY -> "Weekdays at ${t.timeOfDay}"
    ScheduleKind.WEEKLY -> {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val days = t.weekdays.sorted().joinToString(",") { names.getOrElse(it) { "?" } }
        "Weekly at ${t.timeOfDay} on [$days]"
    }
}
