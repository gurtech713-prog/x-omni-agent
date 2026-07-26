package com.omniclaw.app.data.skill

import android.content.Context
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.data.model.SkillCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface SkillRepository {
    val skills: StateFlow<List<Skill>>
    suspend fun reload()
    fun getById(id: String): Skill?
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Singleton
class SkillRepositoryImpl @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : SkillRepository {

    private val _skills = MutableStateFlow(seedSkills())
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /** File storing the user's per-skill enabled/disabled toggles. */
    private val enabledStateFile: File by lazy { File(ctx.filesDir, "skill_enabled.json") }

    override suspend fun reload() = withContext(Dispatchers.IO) {
        val loaded = loadFromAssets() + loadFromFilesDir()
        // Merge: loaded entries take priority over seed entries with the same ID;
        // seed entries with no asset/file counterpart are kept as fallbacks.
        val loadedIds = loaded.map { it.id }.toSet()
        val merged = loaded + _skills.value.filter { it.id !in loadedIds }
        // Apply persisted enabled/disabled state on top of the merge so the
        // user's toggles survive reload + restart.
        val savedState = loadEnabledState()
        _skills.value = merged.map { it.copy(enabled = savedState[it.id] ?: it.enabled) }
    }

    override fun getById(id: String): Skill? = _skills.value.firstOrNull { it.id == id }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        _skills.update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        // Persist the toggle so it survives app restart. Previously setEnabled
        // only updated the in-memory StateFlow, so every disabled skill was
        // silently re-enabled on next launch (seedSkills() sets all to true).
        persistEnabledState()
    }

    // ---- Persistence of enabled/disabled toggles ----

    private fun loadEnabledState(): Map<String, Boolean> {
        val file = enabledStateFile
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readText().lineSequence()
                .filter { it.contains('=') }
                .associate {
                    val (k, v) = it.split('=', limit = 2)
                    k.trim() to v.trim().equals("true", ignoreCase = true)
                }
        }.getOrDefault(emptyMap())
    }

    private suspend fun persistEnabledState() = withContext(Dispatchers.IO) {
        runCatching {
            val text = _skills.value.joinToString("\n") { "${it.id}=${it.enabled}" }
            enabledStateFile.writeText(text)
        }
    }

    /**
     * Load SKILL.md metadata from app/src/main/assets/skills/<id>/SKILL.md.
     * The format is a minimal Markdown: first H1 -> name, first paragraph -> description,
     * bullet items starting with "example:" -> examples.
     */
    private fun loadFromAssets(): List<Skill> {
        val out = mutableListOf<Skill>()
        return runCatching {
            val dirs = ctx.assets.list("skills").orEmpty()
            for (dir in dirs) {
                val md = runCatching {
                    ctx.assets.open("skills/$dir/SKILL.md").bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                val parsed = parseSkillMd(md, dir, "assets/skills/$dir/SKILL.md") ?: continue
                out.add(parsed)
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Load auto-created skills from filesDir/skills/<id>/SKILL.md.
     *
     * These are written at runtime by:
     *   - LearningEngine.maybeAutoCreateSkill (LLM-drafted SKILL.md)
     *   - AgentLoop.handleSkillAction("skill:skill-creator", ...)
     *
     * Previously loadFromAssets only scanned APK-bundled assets, so any skill
     * created at runtime was invisible on the Skills screen until the app was
     * rebuilt. We now also scan filesDir so runtime-created skills appear.
     */
    private fun loadFromFilesDir(): List<Skill> {
        val out = mutableListOf<Skill>()
        val skillsDir = File(ctx.filesDir, "skills")
        if (!skillsDir.exists()) return out
        val dirs = runCatching { skillsDir.listFiles { f -> f.isDirectory } ?: emptyArray() }
            .getOrDefault(emptyArray())
        for (dir in dirs) {
            val mdFile = File(dir, "SKILL.md")
            if (!mdFile.exists()) continue
            val md = runCatching { mdFile.readText() }.getOrNull() ?: continue
            val parsed = parseSkillMd(md, dir.name, mdFile.absolutePath) ?: continue
            out.add(parsed.copy(category = SkillCategory.SKILL_MGMT))
        }
        return out
    }

    private fun parseSkillMd(md: String, fallbackId: String, path: String): Skill? {
        val name = Regex("(?m)^#\\s+(.+)$").find(md)?.groupValues?.getOrNull(1)?.trim() ?: fallbackId
        // First non-blank line that isn't a heading or list item → description.
        val desc = Regex("(?m)^\\s*([^#\\-+*`>].+)\\s*$").find(md)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val examples = Regex("(?m)^[-*+]\\s+(.+)$").findAll(md).map { it.groupValues[1].trim() }.toList()
        return Skill(
            id = fallbackId,
            name = name,
            category = categoryFor(fallbackId),
            description = desc,
            enabled = true,
            examples = examples,
            path = path,
        )
    }

    private fun categoryFor(id: String): SkillCategory = when {
        id.endsWith("search") || id.startsWith("app-") || id.startsWith("amazon") || id.startsWith("reddit") -> SkillCategory.SEARCH_APPS
        id.startsWith("gallery-") || id.startsWith("capcut") || id.startsWith("clipboard") -> SkillCategory.GALLERY_MEDIA
        id.endsWith("-config") || id == "model-config" || id == "channel-config" -> SkillCategory.CONFIG
        id == "skill-creator" -> SkillCategory.SKILL_MGMT
        id == "scheduled-automation" -> SkillCategory.AUTOMATION
        else -> SkillCategory.CONFIG
    }

    /** Built-in seed list — mirrors the bundled skills. */
    private fun seedSkills(): List<Skill> = listOf(
        Skill("app-search", "App search", SkillCategory.SEARCH_APPS,
            "Search inside a target app and return a summary.", true,
            listOf("Search Reddit for budget travel tips and send me the summary."),
            "assets/skills/app-search/SKILL.md"),
        Skill("amazon-search", "Amazon search", SkillCategory.SEARCH_APPS,
            "Search Amazon for items and recommend 3 by price band and sales.", true,
            listOf("On Amazon, search men's light sunscreen shirts; recommend 3 by price band and sales."),
            "assets/skills/amazon-search/SKILL.md"),
        Skill("gallery-qa", "Gallery Q&A", SkillCategory.GALLERY_MEDIA,
            "Answer questions about your local photo gallery.", true,
            listOf("What photos did I take today? Briefly in time order."),
            "assets/skills/gallery-qa/SKILL.md"),
        Skill("gallery-memory", "Gallery memory", SkillCategory.GALLERY_MEDIA,
            "Build / refresh a searchable memory index of your gallery.", true,
            listOf("Sync gallery memory and refresh my profile—scan the latest 20 photos."),
            "assets/skills/gallery-memory/SKILL.md"),
        Skill("capcut-theme-video", "One-tap video", SkillCategory.GALLERY_MEDIA,
            "Stage picks into a temp album, jump to CapCut one-tap flow, export/share.", true,
            listOf("Find parrot-themed photos and make a one-tap video."),
            "assets/skills/capcut-theme-video/SKILL.md"),
        Skill("clipboard-to-shortcut", "Clipboard to shortcut", SkillCategory.GALLERY_MEDIA,
            "Turn the clipboard URL into a named skill.", true,
            listOf("Turn the clipboard URL into a skill named Amazon quick link."),
            "assets/skills/clipboard-to-shortcut/SKILL.md"),
        Skill("model-config", "Model config", SkillCategory.CONFIG,
            "Add an OpenAI-compatible provider at URL xxx; default model xxx.", true,
            listOf("Add an OpenAI-compatible provider at URL xxx; default model xxx."),
            "assets/skills/model-config/SKILL.md"),
        Skill("channel-config", "Channel config", SkillCategory.CONFIG,
            "Configure Discord webhook notifications.", true,
            listOf("Configure Discord notifications to webhook xxx."),
            "assets/skills/channel-config/SKILL.md"),
        Skill("skill-creator", "Skill creator", SkillCategory.SKILL_MGMT,
            "Create a new skill from a description.", true,
            listOf("Create a skill that opens Reddit and saves the third post."),
            "assets/skills/skill-creator/SKILL.md"),
        Skill("scheduled-automation", "Scheduled automation", SkillCategory.AUTOMATION,
            "Set up interval / weekday / weekly scheduled automation.", true,
            listOf("Every Wednesday 10:00 open Reddit, search budget travel."),
            "assets/skills/scheduled-automation/SKILL.md"),
    )
}
