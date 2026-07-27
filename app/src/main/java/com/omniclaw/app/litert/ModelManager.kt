package com.omniclaw.app.litert

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages LiteRT model file lifecycle: resolves asset paths, extracts bundled
 * models to a seekable file (LiteRT requires a File — AssetManager fds break
 * mmap on some devices), and caches extracted models by content hash.
 *
 * Extraction is idempotent: if the extracted file already exists with the
 * expected size, it's reused. A SHA-256 of the full file is used to detect
 * APK updates that change the bundled model (in which case the extracted
 * file is overwritten).
 *
 * Thread safety: the internal cache map is guarded by a synchronized block.
 * Safe to call from any thread.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private val cache = mutableMapOf<String, File>()
    private val lock = Any()

    /**
     * Resolve [modelPath] to a seekable [File] on disk.
     *
     * Accepts:
     *   - Absolute paths (e.g. "/data/.../model.tflite") — returned as-is.
     *   - "assets://path" — extracted from assets to filesDir.
     *   - "models/name.tflite" — resolved from assets/models/.
     *   - Bare filenames — resolved from assets/models/.
     */
    fun resolveToFile(modelPath: String): File {
        synchronized(lock) {
            cache[modelPath]?.let { if (it.exists() && it.length() > 0) return it }
        }

        val absolute = File(modelPath)
        if (absolute.isAbsolute && absolute.exists()) {
            synchronized(lock) { cache[modelPath] = absolute }
            return absolute
        }

        val assetPath = when {
            modelPath.startsWith("assets://") -> modelPath.removePrefix("assets://")
            modelPath.startsWith("models/") -> modelPath
            modelPath.contains('/') -> modelPath
            else -> "models/$modelPath"
        }
        val outDir = File(ctx.filesDir, "litert_models").apply { mkdirs() }
        val outFile = File(outDir, assetPath.replace('/', '_'))

        // Check if the existing extracted file matches the bundled asset.
        if (outFile.exists() && outFile.length() > 0 && !assetChanged(assetPath, outFile)) {
            synchronized(lock) { cache[modelPath] = outFile }
            return outFile
        }

        // Extract the asset atomically: write to a temp file, then rename.
        val tmpFile = File(outFile.absolutePath + ".tmp")
        runCatching {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            }
            // Verify the extracted file is non-empty before renaming.
            if (tmpFile.length() == 0L) {
                tmpFile.delete()
                throw LiteRtModelException("Asset '$assetPath' extracted as empty file — model not found in APK")
            }
            // Atomic rename — prevents partial files from being used after a crash.
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                throw LiteRtModelException("Failed to rename temp file to '${outFile.absolutePath}'")
            }
        }.onFailure {
            runCatching { tmpFile.delete() }
            if (it is LiteRtModelException) throw it
            throw LiteRtModelException("Could not extract model '$assetPath': ${it.message}")
        }

        Log.i(TAG, "Extracted LiteRT model '$assetPath' -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        synchronized(lock) { cache[modelPath] = outFile }
        return outFile
    }

    /**
     * Check if the bundled asset differs from the extracted file.
     * Compares file length first (cheap), then hashes the full file content
     * to detect any corruption or truncation.
     */
    private fun assetChanged(assetPath: String, extracted: File): Boolean {
        // Get asset size for length comparison.
        val assetSize = runCatching {
            ctx.assets.openFd(assetPath).use { it.length }
        }.getOrNull()

        // If we can determine the asset size, check length first (cheap check).
        if (assetSize != null && extracted.length() != assetSize) {
            return true
        }

        // Hash the full asset content.
        val assetHash = runCatching {
            ctx.assets.open(assetPath).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n > 0) {
                    digest.update(buf, 0, n)
                    n = input.read(buf)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return false  // can't read asset — don't trigger re-extract

        // Hash the full extracted file content.
        val fileHash = runCatching {
            extracted.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n > 0) {
                    digest.update(buf, 0, n)
                    n = input.read(buf)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return true  // can't read extracted — re-extract

        return assetHash != fileHash
    }

    /** Clear the resolved-file cache (does NOT delete files on disk). */
    fun clearCache() {
        synchronized(lock) { cache.clear() }
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}

class LiteRtModelException(message: String) : RuntimeException(message)
