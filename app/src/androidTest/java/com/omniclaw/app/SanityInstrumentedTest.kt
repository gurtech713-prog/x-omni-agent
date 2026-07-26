package com.omniclaw.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — runs on a real device or emulator.
 * Verifies that the application context loads with the right package name.
 */
@RunWith(AndroidJUnit4::class)
class SanityInstrumentedTest {

    @Test
    fun packageName_isCorrect() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // applicationId from build.gradle.kts = "com.aistudio.xomniclaw.mgypws"
        // + ".debug" suffix in debug buildType → "com.aistudio.xomniclaw.mgypws.debug"
        assertEquals("com.aistudio.xomniclaw.mgypws.debug", ctx.packageName)
    }
}
