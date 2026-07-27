package com.omniclaw.app.deeplink

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep-link & reproducible-flow support.
 *
 * Mirrors the original X-OmniClaw "deep links & reproducible flows" feature:
 * bookmarks and deep links compress long paths into one-shot commands.
 *
 * Powers the clipboard-to-shortcut skill: reads the clipboard URL, wraps it
 * as a named skill, and persists it so the user can later say "Open <name>"
 * to trigger the deep link in one step.
 */
@Singleton
class DeepLinkManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    data class Bookmark(
        val id: String,
        val name: String,
        val uri: String,
        val createdAt: Long,
    )

    /** Read the clipboard and return its content if it's a URL. */
    fun readClipboardUrl(): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(ctx).toString().trim()
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        // Also accept Android deep links like "amazon://item?id=123"
        if (text.contains("://")) return text
        return null
    }

    /** Persist a URL as a named bookmark under filesDir/bookmarks/<id>.md. */
    fun saveBookmark(name: String, url: String): String {
        val baseId = "link-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        val dir = java.io.File(ctx.filesDir, "bookmarks").apply { mkdirs() }
        // Ensure unique filename: if a bookmark with this id already exists,
        // append -2, -3, etc. so we never silently overwrite a previous
        // bookmark whose name sanitizes to the same id.
        var id = baseId
        var n = 2
        while (java.io.File(dir, "$id.md").exists()) {
            id = "$baseId-$n"
            n++
            if (n > 999) { id = "$baseId-${System.currentTimeMillis()}"; break }
        }
        val sb = StringBuilder()
        sb.appendLine("# $name")
        sb.appendLine()
        sb.appendLine("Deep-link bookmark. Trigger phrase launches the URL.")
        sb.appendLine("- Open $name")
        sb.appendLine()
        sb.appendLine("## URL")
        sb.appendLine("```")
        sb.appendLine(url)
        sb.appendLine("```")
        // Atomic write via a temp file + rename so a crash mid-write doesn't
        // leave a corrupted bookmark file that breaks listBookmarks().
        val target = java.io.File(dir, "$id.md")
        val tmp = java.io.File(dir, "$id.md.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystems; fall back to copy + delete.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return id
    }

    /**
     * Launch a bookmark by matching its name against the trigger phrase.
     * Previously this used `phrase.contains(it.name)` — a substring match
     * that caused false positives (a bookmark named "Am" would match the
     * phrase "Open Amazon"). Now we match on whole-word boundaries and
     * prefer the longest matching name so "Amazon" wins over "Am".
     */
    fun launchByPhrase(phrase: String): Boolean {
        val all = listBookmarks()
        if (all.isEmpty()) return false
        // Find bookmarks whose name appears as a whole word in the phrase.
        val matches = all.mapNotNull { b ->
            val firstChar = b.name.firstOrNull()
            val lastChar = b.name.lastOrNull()
            val isWordChar: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }
            val prefix = if (firstChar != null && isWordChar(firstChar)) "(?<!\\w)" else ""
            val suffix = if (lastChar != null && isWordChar(lastChar)) "(?!\\w)" else ""
            val pattern = Regex("$prefix${Regex.escape(b.name)}$suffix", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(phrase)) b to b.name.length else null
        }
        if (matches.isEmpty()) return false
        // Longest-name match wins (most specific).
        val best = matches.maxByOrNull { it.second }?.first ?: return false
        return launchUri(best.uri)
    }

    /** Launch a raw URI (http/https or app deep link). */
    fun launchUri(uri: String): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        val scheme = parsed.scheme?.lowercase()
        // S-M4: reject dangerous schemes — `intent:` can carry Intent extras
        // that re-launch arbitrary components, `javascript:` runs script in
        // the context of whatever app handles the URL. Allow only safe
        // schemes (http/https and other app deep links).
        require(scheme != "intent" && scheme != "javascript") {
            "Scheme $scheme not allowed"
        }
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /** List all saved bookmarks. */
    fun listBookmarks(): List<Bookmark> {
        val dir = java.io.File(ctx.filesDir, "bookmarks")
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".md") }
            .mapNotNull { f ->
                runCatching {
                    val text = f.readText()
                    val name = Regex("(?m)^#\\s+(.+)$").find(text)?.groupValues?.getOrNull(1)?.trim()
                        ?: f.nameWithoutExtension
                    val url = Regex("(?m)^```\\s*\n(.+?)\n```", RegexOption.DOT_MATCHES_ALL)
                        .find(text)?.groupValues?.getOrNull(1)?.trim()
                        ?: return@mapNotNull null
                    Bookmark(
                        id = f.nameWithoutExtension,
                        name = name,
                        uri = url,
                        createdAt = f.lastModified(),
                    )
                }.getOrNull()
            }
    }
}
