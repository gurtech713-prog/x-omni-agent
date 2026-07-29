package com.omniclaw.app.data.skill

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom skill marketplace — lets users download community-created SKILL.md
 * files from a URL and install them as skills.
 *
 * The downloaded SKILL.md is saved to filesDir/skills/<id>/SKILL.md, where
 * [SkillRepositoryImpl.loadFromFilesDir] picks it up on the next reload.
 *
 * URL format: any HTTPS URL pointing to a raw SKILL.md file (e.g. a GitHub
 * raw URL, a Gist, or a self-hosted skill registry).
 */
@Singleton
class SkillMarketplace @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
) {

    /**
     * Download a SKILL.md from the given URL and install it.
     *
     * @param url the HTTPS URL to the raw SKILL.md content
     * @param skillId the ID to use for the skill (derived from the URL or
     *        provided by the caller). If null, a hash of the URL is used.
     * @return the installed skill ID on success, or null on failure
     */
    suspend fun installFromUrl(url: String, skillId: String? = null): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val id = skillId?.takeIf { it.isNotBlank() } ?: deriveIdFromUrl(url)
        try {
            val req = Request.Builder().url(url).build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext null
            }
            val body = resp.body?.string() ?: return@withContext null
            if (body.isBlank()) return@withContext null
            // Basic validation: must contain at least an H1 heading
            if (!body.contains("# ")) {
                return@withContext null
            }
            val dir = File(ctx.filesDir, "skills/$id").apply { mkdirs() }
            val target = File(dir, "SKILL.md")
            val tmp = File(dir, "SKILL.md.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.writeText(body)
                tmp.delete()
            }
            return@withContext id
        } catch (e: Exception) {
            return@withContext null
        }
    }

    /**
     * Derive a skill ID from a URL. Uses the last path segment (without
     * extension), or a hash of the URL if that's empty.
     */
    private fun deriveIdFromUrl(url: String): String {
        val pathSegment = url.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
        if (pathSegment.isNotBlank() && pathSegment.matches(Regex("[a-z0-9\\-]+"))) {
            return "marketplace-$pathSegment"
        }
        return "marketplace-${url.hashCode().toString().replace("-", "n")}"
    }

    /**
     * Delete an installed marketplace skill.
     */
    suspend fun uninstall(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "skills/$skillId")
        if (!dir.exists()) return@withContext false
        return@withContext dir.deleteRecursively()
    }

    /**
     * List all installed marketplace skills (those in filesDir/skills/ that
     * are NOT bundled in assets).
     */
    fun listInstalled(): List<String> {
        val dir = File(ctx.filesDir, "skills")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
    }
}
