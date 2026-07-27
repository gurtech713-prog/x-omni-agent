package com.omniclaw.app.gallery

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.omniclaw.app.data.memory.MemoryRepository
import com.omniclaw.app.data.model.MemoryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device's local photo gallery via MediaStore.
 *
 * Powers three of the bundled skills:
 *   - gallery-qa       (answer questions about today's photos, etc.)
 *   - gallery-memory   (build / refresh a searchable memory index)
 *   - capcut-theme-video (filter by theme keyword, stage into a temp album)
 */
@Singleton
class GalleryScanner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val memory: MemoryRepository,
) {

    data class Photo(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val dateTaken: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val bucket: String,
    )

    /**
     * Outcome of a gallery query (M-43). Lets callers distinguish "no photos"
     * ([Ok] with an empty list) from "permission denied" ([NoPermission]) and
     * other failures ([Error]) — previously a revoked media permission was
     * silently swallowed into an empty list.
     */
    sealed class GalleryResult {
        data class Ok(val photos: List<Photo>) : GalleryResult()
        object NoPermission : GalleryResult()
        object Error : GalleryResult()
    }

    /** Returns the most recent N photos, newest first. */
    suspend fun recent(limit: Int = 20): List<Photo> =
        (recentResult(limit) as? GalleryResult.Ok)?.photos.orEmpty()

    /** [recent] but preserving the full [GalleryResult] for callers that need it. */
    suspend fun recentResult(limit: Int = 20): GalleryResult = withContext(Dispatchers.IO) {
        query(
            selection = null,
            selectionArgs = null,
            limit = limit,
        )
    }

    /** Returns photos taken since [sinceEpoch]. */
    suspend fun since(sinceEpoch: Long, limit: Int = 200): List<Photo> =
        (sinceResult(sinceEpoch, limit) as? GalleryResult.Ok)?.photos.orEmpty()

    /** [since] but preserving the full [GalleryResult] for callers that need it. */
    suspend fun sinceResult(sinceEpoch: Long, limit: Int = 200): GalleryResult = withContext(Dispatchers.IO) {
        query(
            selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ?",
            selectionArgs = arrayOf(sinceEpoch.toString()),
            limit = limit,
        )
    }

    /** Free-text filter by file name / bucket name. */
    suspend fun search(query: String, limit: Int = 100): List<Photo> =
        (searchResult(query, limit) as? GalleryResult.Ok)?.photos.orEmpty()

    /** [search] but preserving the full [GalleryResult] for callers that need it. */
    suspend fun searchResult(query: String, limit: Int = 100): GalleryResult = withContext(Dispatchers.IO) {
        // Escape SQL LIKE wildcards in the user query so a search for "50%_off"
        // doesn't match far more than intended. We escape % and _ and wrap the
        // whole thing in a LIKE pattern with the ESCAPE clause.
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val q = "%$escaped%"
        query(
            selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            selectionArgs = arrayOf(q, q),
            limit = limit,
        )
    }

    private fun query(selection: String?, selectionArgs: Array<String>?, limit: Int): GalleryResult {
        val out = mutableListOf<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        // V-M5: the `LIMIT` clause inside sortOrder is silently ignored on
        // Android < 30 (the SQLite shim strips it) and may not be honored by
        // all MediaStore implementations even on 30+. Use the official Bundle
        // API on R+; fall back to plain sortOrder (no LIMIT) on older devices
        // and post-filter the cursor to the first `limit` rows.
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val args = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        1, // ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                }
                ctx.contentResolver.query(uri, projection, args, null)
            } else {
                val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                ctx.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val bIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                // V-M5: post-filter for the older-API path (no Bundle LIMIT).
                // Stop iterating once we've collected `limit` rows.
                while (c.moveToNext() && out.size < limit) {
                    out.add(
                        Photo(
                            id = c.getLong(idIdx),
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx)),
                            displayName = c.getString(nameIdx).orEmpty(),
                            dateTaken = c.getLong(dateIdx),
                            sizeBytes = c.getLong(sizeIdx),
                            width = c.getInt(wIdx),
                            height = c.getInt(hIdx),
                            bucket = c.getString(bIdx).orEmpty(),
                        ),
                    )
                }
            }
            GalleryResult.Ok(out)
        } catch (se: SecurityException) {
            // Media permission revoked (possibly mid-query). Surface this distinctly
            // from "no photos" so callers can prompt for permission (M-43).
            Log.w(TAG, "Gallery query denied (permission revoked): ${se.message}")
            GalleryResult.NoPermission
        } catch (t: Throwable) {
            Log.w(TAG, "Gallery query failed: ${t.message}")
            GalleryResult.Error
        }
    }

    /**
     * gallery-memory skill: scan the latest N photos and distill them into
     * long-term memory entries (one EPISODE per batch + one FACT per bucket).
     */
    suspend fun syncMemory(scanCount: Int = 20): Int {
        val photos = when (val result = recentResult(scanCount)) {
            is GalleryResult.NoPermission -> {
                Log.w(TAG, "syncMemory: no media permission — nothing scanned")
                return 0
            }
            is GalleryResult.Error -> {
                Log.w(TAG, "syncMemory: gallery query errored — nothing scanned")
                return 0
            }
            is GalleryResult.Ok -> result.photos
        }
        if (photos.isEmpty()) return 0
        // Group by bucket (camera roll, screenshots, downloads, etc.)
        val byBucket = photos.groupBy { it.bucket }
        val now = System.currentTimeMillis()
        memory.add(
            MemoryEntry.MemoryKind.EPISODE,
            "Scanned $scanCount photos at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))}. " +
                "Buckets: ${byBucket.keys.joinToString { "$it=${byBucket[it]?.size}" }}.",
            "gallery-memory",
        )
        byBucket.forEach { (bucket, items) ->
            memory.add(
                MemoryEntry.MemoryKind.FACT,
                "Gallery bucket '$bucket' has ${items.size} recent photos (latest: ${items.first().displayName}).",
                "gallery-memory",
            )
        }
        return photos.size
    }

    /**
     * capcut-theme-video skill: filter photos by theme keyword and return
     * a list of URIs suitable for staging into a temp album.
     */
    suspend fun stageForTheme(theme: String, limit: Int = 30): List<Uri> =
        (searchResult(theme, limit) as? GalleryResult.Ok)?.photos.orEmpty().map { it.uri }

    companion object {
        private const val TAG = "GalleryScanner"
    }
}
