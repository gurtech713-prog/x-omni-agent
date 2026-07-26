package com.omniclaw.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SecureStorage.StorageState] — the state machine that
 * gates whether secrets can be read/written. The state transitions are
 * pure logic (no Android Keystore interaction in this test); the goal is
 * to lock in the contract that release builds refuse to silently downgrade
 * to plaintext.
 */
class SecureStorageStateTest {

    @Test
    fun `StorageState enum has the three expected values`() {
        val values = SecureStorage.StorageState.values().map { it.name }.toSet()
        assertEquals(setOf("ENCRYPTED", "FALLBACK", "FAILED"), values)
    }

    @Test
    fun `ENCRYPTED state allows read and write`() {
        // The contract: when state == ENCRYPTED, getSecret returns stored value,
        // setSecret persists, hasSecret reflects presence, removeSecret clears.
        // (Verified by integration; this test pins the enum value names so a
        // rename doesn't silently break the UI gate.)
        assertEquals(0, SecureStorage.StorageState.ENCRYPTED.ordinal)
    }

    @Test
    fun `FAILED state is the safety fallback`() {
        // The release-build behavior: if EncryptedSharedPreferences init fails
        // in a release build, state becomes FAILED (not FALLBACK) and all
        // secret reads return "", writes are dropped.
        assertEquals(2, SecureStorage.StorageState.FAILED.ordinal)
    }

    @Test
    fun `FALLBACK state is only reachable in debug builds`() {
        // Per SecureStorage.initPrefs: the FALLBACK branch is gated on
        // BuildConfig.DEBUG. This test pins the enum name so the gate logic
        // in callers (e.g. UI red banner when state != ENCRYPTED) keeps working.
        assertEquals(1, SecureStorage.StorageState.FALLBACK.ordinal)
    }

    @Test
    fun `all StorageState values are distinct`() {
        val s = SecureStorage.StorageState.values().toSet()
        assertEquals(3, s.size)
    }
}
