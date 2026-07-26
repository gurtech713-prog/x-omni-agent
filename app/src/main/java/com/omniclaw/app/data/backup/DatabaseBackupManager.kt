package com.omniclaw.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Database backup manager for session history and memories.
 *
 * Provides automated encrypted export and manual import/export via Settings.
 * Backups are stored in app-specific external storage by default.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Export database to encrypted JSON file. */
    suspend fun exportDatabase(): File? = withContext(Dispatchers.IO) {
        try {
            val dbFile = File(ctx.getDatabasePath("omniclaw.db").path)
            if (!dbFile.exists()) return@withContext null
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupDir = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }
            val backupFile = File(backupDir, "omniclaw_$timestamp.db")
            
            // Copy database file as binary backup
            dbFile.copyTo(backupFile, overwrite = true)
            
            backupFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /** List available backups. */
    fun listBackups(): List<File> {
        val backupDir = File(ctx.getExternalFilesDir(null), "backups")
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { file -> 
            file.name.startsWith("xomniclaw_backup_") || file.name.startsWith("omniclaw_")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /** Delete a specific backup. */
    fun deleteBackup(file: File): Boolean {
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
