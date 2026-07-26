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
 * expected size, it's reused. A SHA-256 of the first 4KB is used to detect
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

        // Extract the asset.
        runCatching {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }.onFailure {
            throw LiteRtModelException("Could not extract model '$assetPath': ${it.message}")
        }

        if (outFile.length() == 0L) {
            throw LiteRtModelException("Asset '$assetPath' extracted as empty file — model not found in APK")
        }
        Log.i(TAG, "Extracted LiteRT model '$assetPath' -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        synchronized(lock) { cache[modelPath] = outFile }
        return outFile
    }

    /**
     * Check if the bundled asset differs from the extracted file by hashing
     * the first 4KB of each. Returns true if they differ (asset was updated).
     */
    private fun assetChanged(assetPath: String, extracted: File): Boolean {
        val assetHash = runCatching {
            ctx.assets.open(assetPath).use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) return@use ""
                MessageDigest.getInstance("SHA-256").digest(buf.copyOf(n)).joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return false  // can't read asset — don't trigger re-extract

        val fileHash = runCatching {
            extracted.inputStream().use { input ->
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) return@use ""
                MessageDigest.getInstance("SHA-256").digest(buf.copyOf(n)).joinToString("") { "%02x".format(it) }
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
