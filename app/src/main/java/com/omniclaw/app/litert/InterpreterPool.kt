package com.omniclaw.app.litert

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.tensorflow.lite.Interpreter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A pool of [Interpreter] instances for a single model, enabling limited
 * concurrent inference.
 *
 * The LiteRT [Interpreter] is NOT thread-safe — concurrent `run()` calls
 * corrupt the I/O buffers. The [LiteRtEngine] serializes inference via a
 * Mutex, which is correct but limits throughput to one inference at a time.
 *
 * For models that benefit from parallelism (e.g. batch embedding
 * generation), [InterpreterPool] maintains up to [maxPoolSize] interpreters
 * (default 2), each with its own Mutex. Callers acquire an interpreter
 * via [acquire], run inference, and release it via [release]. The pool
 * reuses interpreters across calls — no re-construction cost.
 *
 * Memory note: each interpreter holds a full copy of the model weights in
 * native memory. A 1GB model with poolSize=2 consumes 2GB. Tune
 * [maxPoolSize] carefully for your device.
 */
@Singleton
class InterpreterPool @Inject constructor() {

    data class PooledInterpreter(
        val interpreter: Interpreter,
        val gpuDelegate: org.tensorflow.lite.gpu.GpuDelegate?,
        val nnApiDelegate: org.tensorflow.lite.nnapi.NnApiDelegate?,
        val mutex: Mutex,
    )

    data class PoolKey(val modelPath: String, val useGpu: Boolean, val useNnapi: Boolean)

    private val pools = ConcurrentHashMap<PoolKey, MutableList<PooledInterpreter>>()
    private val poolLocks = ConcurrentHashMap<PoolKey, Mutex>()
    // Per-key count of creation slots reserved (but not yet materialized) so
    // that factory() can run OUTSIDE poolLock without overshooting maxPoolSize.
    private val reservedSlots = ConcurrentHashMap<PoolKey, AtomicInteger>()
    private val warmupCount = AtomicLong(0)

    /**
     * Acquire an interpreter for (modelPath, useGpu, useNnapi).
     *
     * The caller gets exclusive access to the returned interpreter until
     * [release] is called with the same [LeasedInterpreter]. If the pool
     * is exhausted, a new interpreter is created (up to [maxPoolSize]).
     *
     * If the pool is at capacity, this suspends until any interpreter becomes
     * free. The acquisition is atomic — no TOCTOU race between "found a free
     * slot" and "locked it".
     */
    suspend fun acquire(
        modelPath: String,
        useGpu: Boolean,
        useNnapi: Boolean,
        maxPoolSize: Int = 2,
        factory: () -> PooledInterpreter,
    ): LeasedInterpreter {
        val key = PoolKey(modelPath, useGpu, useNnapi)
        val poolLock = poolLocks.computeIfAbsent(key) { Mutex() }
        val reserved = reservedSlots.computeIfAbsent(key) { AtomicInteger(0) }
        // Loop until we acquire (or create) an interpreter. The loop is
        // required because two coroutines may both see "no free slot" and
        // both wait on the same mutex; when one releases, only one waiter
        // wakes up. The other waits again, then re-checks for free slots
        // before deciding to wait further.
        while (true) {
            // Carry the decision out of the lock via LOCAL vars — never a shared
            // instance field: concurrent acquire() calls for DIFFERENT models would
            // otherwise overwrite each other's chosen interpreter and lease the
            // wrong one.
            var leased: LeasedInterpreter? = null
            var waitFor: PooledInterpreter? = null
            var shouldCreate = false
            poolLock.withLock {
                val pool = pools.computeIfAbsent(key) { mutableListOf() }
                // Find a free interpreter (one whose mutex is unlocked).
                for (pi in pool) {
                    if (pi.mutex.tryLock()) {
                        leased = LeasedInterpreter(pi, key, this)
                        return@withLock
                    }
                }
                // All in use — reserve a creation slot if below capacity. We only
                // RESERVE here; the expensive factory() call runs OUTSIDE poolLock
                // (below) so other acquirers can progress during construction.
                if (pool.size + reserved.get() < maxPoolSize) {
                    reserved.incrementAndGet()
                    shouldCreate = true
                    return@withLock
                }
                // Pool at capacity — remember which interpreter to wait on, then
                // release poolLock so other acquirers can progress.
                waitFor = pool.first()
            }
            leased?.let { return it }
            if (shouldCreate) {
                // Construct WITHOUT holding poolLock (H-43): model load can be
                // slow/native and must not block other acquirers of this model.
                val created = runCatching { factory() }.getOrNull()
                poolLock.withLock {
                    val pool = pools.computeIfAbsent(key) { mutableListOf() }
                    reserved.decrementAndGet()
                    if (created != null) {
                        pool.add(created)
                        created.mutex.lock()
                        return LeasedInterpreter(created, key, this)
                    }
                }
                // factory() failed — loop to retry or wait on an existing slot.
                continue
            }
            val chosen = waitFor ?: continue  // null only on a race with closeAll → retry
            // Acquire exclusively with lock() (NOT withLock): the lease must KEEP
            // the mutex held until release(). withLock's finally block would unlock
            // on return, handing out an unheld interpreter → concurrent run() corruption.
            // Bound the wait (H-44): a stuck/leaked interpreter must not wedge this
            // caller forever. On timeout, retry the loop to re-evaluate free slots
            // or create a new interpreter.
            val locked = withTimeoutOrNull(30_000) { chosen.mutex.lock() }
            if (locked == null) continue
            return LeasedInterpreter(chosen, key, this)
        }
    }

    /** Release a leased interpreter back to the pool. */
    fun release(lease: LeasedInterpreter) {
        lease.pooled.mutex.unlock()
    }

    /** Close all interpreters + delegates in all pools. */
    suspend fun closeAll() {
        for ((key, pool) in pools.toMap()) {
            val poolLock = poolLocks[key] ?: continue
            poolLock.withLock {
                pool.forEach { pi ->
                    // Drain any in-flight inference before closing (C-12): the
                    // TFLite Interpreter is not thread-safe, so closing during a
                    // concurrent run() causes a native use-after-free / SIGSEGV.
                    // Acquiring pi.mutex waits for the current holder to finish;
                    // the timeout bounds shutdown so a stuck lease can't hang it.
                    runCatching {
                        withTimeoutOrNull(30_000) {
                            pi.mutex.withLock {
                                runCatching { pi.interpreter.close() }
                            }
                        }
                    }
                    runCatching { pi.gpuDelegate?.close() }
                    runCatching { pi.nnApiDelegate?.close() }
                }
                pool.clear()
            }
        }
        pools.clear()
        poolLocks.clear()
        reservedSlots.clear()
    }

    /** Number of interpreters currently pooled for a given key. */
    fun poolSize(modelPath: String, useGpu: Boolean, useNnapi: Boolean): Int {
        val key = PoolKey(modelPath, useGpu, useNnapi)
        return pools[key]?.size ?: 0
    }

    /** A held lease on an interpreter — call [close] (or [release]) when done. */
    class LeasedInterpreter(
        val pooled: PooledInterpreter,
        private val key: PoolKey,
        private val pool: InterpreterPool,
    ) : AutoCloseable {
        val interpreter: Interpreter get() = pooled.interpreter
        // Idempotency guard (M-47): close() may be invoked both by a
        // try-finally { lease.close() } and an explicit release; only the first
        // call should unlock the mutex, otherwise the second throws
        // IllegalStateException: Mutex is not locked.
        private var released = false
        override fun close() {
            if (!released) {
                released = true
                pool.release(this)
            }
        }
    }

    companion object {
        private const val TAG = "InterpreterPool"
    }
}
