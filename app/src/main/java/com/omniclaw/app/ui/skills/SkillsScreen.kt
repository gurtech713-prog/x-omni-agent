package com.omniclaw.app.ui.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniclaw.app.core.theme.Motion
import com.omniclaw.app.core.theme.OmniMono
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.data.model.SkillCategory
import com.omniclaw.app.ui.components.OmniBadge
import com.omniclaw.app.ui.components.OmniDivider
import com.omniclaw.app.ui.components.OmniEmptyState
import com.omniclaw.app.ui.components.OmniSectionHeader
import com.omniclaw.app.ui.components.OmniTopBar

@Composable
fun SkillsScreen() {
    val vm: SkillsViewModel = hiltViewModel()
    val skills by vm.skills.collectAsStateWithLifecycle()

    val grouped = skills.groupBy { it.category }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OmniTopBar(title = "Skills", subtitle = "${skills.size} bundled · ${skills.count { it.enabled }} enabled")
        OmniDivider()

        if (skills.isEmpty()) {
            OmniEmptyState(
                title = "No skills loaded",
                subtitle = "Skills load from assets/skills/<id>/SKILL.md",
                modifier = Modifier.padding(top = 40.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            grouped.forEach { (cat, items) ->
                item { OmniSectionHeader(title = categoryLabel(cat)) }
                items(items, key = { it.id }) { skill ->
                    AnimatedVisibility(visible = true, enter = fadeIn(tween(Motion.DurationMedium))) {
                        SkillRow(skill) { enabled -> vm.toggle(skill.id, enabled) }
                    }
                    OmniDivider()
                }
            }
            item {
                OmniSectionHeader("Extending")
                Box(Modifier.padding(20.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "Drop a new folder at",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "assets/skills/<your-id>/SKILL.md",
                                fontFamily = OmniMono,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "with an H1 title, a description, and bullet examples. Rebuild — the Skills screen auto-loads it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
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

private fun categoryLabel(c: SkillCategory): String = when (c) {
    SkillCategory.SEARCH_APPS -> "Search & Apps"
    SkillCategory.GALLERY_MEDIA -> "Gallery & Media"
    SkillCategory.CONFIG -> "Configuration"
    SkillCategory.SKILL_MGMT -> "Skill Management"
    SkillCategory.AUTOMATION -> "Automation"
}
