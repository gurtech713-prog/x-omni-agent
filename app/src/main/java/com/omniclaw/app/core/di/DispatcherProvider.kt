package com.omniclaw.app.core.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier annotations for the four canonical coroutine dispatchers.
 *
 * Inject these via Hilt rather than referencing [Dispatchers] directly so
 * unit tests can swap in [kotlinx.coroutines.test.StandardTestDispatcher]
 * via a test-only module. This is the recommended pattern per the Android
 * Coroutines + Hilt testing guide:
 * https://dagger.dev/hilt/testing
 */
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher

/**
 * Abstraction over [Dispatchers] for testability.
 *
 * Production code should inject this and call [io] / [default] / [main]
 * instead of [Dispatchers.IO] / [Dispatchers.Default] / [Dispatchers.Main].
 * Tests replace the provider with a test dispatcher to control virtual time
 * and avoid real thread hops.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
    val mainImmediate: CoroutineDispatcher
}

/**
 * Production implementation — delegates to [Dispatchers].
 * Bound as a [Singleton] in [AppModule] so every class shares one instance.
 */
@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
}
