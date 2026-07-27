package com.omniclaw.app.data.backup

import android.content.Context
import android.util.Log
import com.omniclaw.app.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Database backup manager for session history and memories.
 *
 * Provides automated encrypted export and manual import/export via Settings.
 * Backups are stored in app-specific external storage by default, always
 * encrypted (PBKDF2-derived AES-GCM) — the DB holds full conversation history
 * and must never be written to external storage in plaintext.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
) {

    companion object {
        private const val TAG = "DatabaseBackupManager"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
    }

    /**
     * Export database to an encrypted backup file.
     *
     * The backup is encrypted with AES-GCM using a key derived from the
     * user-supplied [passphrase] via PBKDF2 (see [deriveKey]).
     *
     * D-H7: the entire checkpoint + copy is wrapped in `db.runInTransaction { }`
     * so Room's write lock is held for the duration of the copy. This prevents
     * the agent loop (or any other writer) from committing new rows into the
     * main DB / WAL file mid-copy, which would previously produce a backup
     * that was internally inconsistent (some rows from before the copy started,
     * some from after). Trade-off: there is a brief write stall for the duration
     * of the copy (typically <1s for a few-MB DB) — UI reads continue to work
     * because Room's WAL allows concurrent readers.
     */
    suspend fun exportDatabase(passphrase: String): File? = withContext(Dispatchers.IO) {
        try {
            val dbFile = File(ctx.getDatabasePath("omniclaw.db").path)
            if (!dbFile.exists()) return@withContext null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupDir = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }
            val backupFile = File(backupDir, "omniclaw_$timestamp.db.enc")

            // Encrypt the copy via CipherOutputStream. File layout:
            //   [salt(16)] [iv(12)] [AES-GCM ciphertext + auth tag]
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }

            // D-H7: hold Room's write lock for the duration of the checkpoint +
            // copy so concurrent writes can't tear the snapshot. Reads still
            // proceed via WAL.
            db.runInTransaction {
                // The DB runs in WAL mode, so recent writes may still live in the
                // sidecar `omniclaw.db-wal` file. Checkpoint (TRUNCATE) flushes the
                // WAL into the main file so a single-file copy is a complete,
                // consistent snapshot. Run the checkpoint through Room's own open
                // helper (rather than a separate SQLiteDatabase handle) so it is
                // coordinated with Room's writer. Best-effort: if the checkpoint
                // fails we still copy the main file rather than aborting the backup.
                runCatching {
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE);").close()
                }
                FileOutputStream(backupFile).use { fos ->
                    fos.write(salt)
                    fos.write(iv)
                    CipherOutputStream(fos, cipher).use { cos ->
                        dbFile.inputStream().use { input -> input.copyTo(cos) }
                    }
                }
            }

            backupFile
        } catch (e: Exception) {
            // D-L2: use Log.e (not printStackTrace) so the failure surfaces in
            // Logcat with a proper tag + stacktrace, and is captured by crash
            // reporters. printStackTrace() writes to stderr which is lost on
            // Android (no stderr in a normal app process) and never reaches
            // Logcat.
            Log.e(TAG, "exportDatabase failed", e)
            null
        }
    }

    /** Derive a 256-bit AES key from [passphrase] and [salt] via PBKDF2-HMAC-SHA256. */
    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
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
        // Path-containment check: only allow deleting files that canonicalize to
        // inside the backups directory. Without this, a caller-supplied path could
        // traverse (e.g. "../../databases/omniclaw.db") and delete any app file.
        val backupDir = File(ctx.getExternalFilesDir(null), "backups").canonicalFile
        val target = file.canonicalFile
        require(target.path.startsWith(backupDir.path + File.separator)) {
            "Refusing to delete a file outside the backups directory: ${file.path}"
        }
        return runCatching { target.delete() }.getOrDefault(false)
    }
}
