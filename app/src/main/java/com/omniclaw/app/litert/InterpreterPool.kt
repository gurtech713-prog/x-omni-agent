package com.omniclaw.app.litert

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.tensorflow.lite.Interpreter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    // V-M15: closed flag prevents a concurrent acquire() from repopulating the
    // pool after closeAll() has torn it down. Volatile so the read outside the
    // lock sees the write performed inside closeAll().
    @Volatile
    private var closed = false

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
        // V-M15: refuse new acquisitions once closeAll() has run.
        if (closed) throw IllegalStateException("InterpreterPool is closed")
        val key = PoolKey(modelPath, useGpu, useNnapi)
        val poolLock = poolLocks.computeIfAbsent(key) { Mutex() }
        val reserved = reservedSlots.computeIfAbsent(key) { AtomicInteger(0) }
        // V-C2: track consecutive factory() failures per acquire() call so a
        // permanently-broken factory doesn't loop forever. V-H7: track
        // consecutive lease timeouts so a stuck interpreter wedges the caller
        // for at most 3 × 30 s instead of indefinitely.
        var factoryFailures = 0
        var consecutiveTimeouts = 0
        // Loop until we acquire (or create) an interpreter. The loop is
        // required because two coroutines may both see "no free slot" and
        // both wait on the same mutex; when one releases, only one waiter
        // wakes up. The other waits again, then re-checks for free slots
        // before deciding to wait further.
        while (true) {
            if (closed) throw IllegalStateException("InterpreterPool is closed")
            // Carry the decision out of the lock via LOCAL vars — never a shared
            // instance field: concurrent acquire() calls for DIFFERENT models would
            // otherwise overwrite each other's chosen interpreter and lease the
            // wrong one.
            var leased: LeasedInterpreter? = null
            var waitFor: PooledInterpreter? = null
            var shouldCreate = false
            poolLock.withLock {
                if (closed) throw IllegalStateException("InterpreterPool is closed")
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
                // V-C1: use firstOrNull() — pool may be transiently empty when all
                // maxPoolSize slots are reserved (factory() running concurrently)
                // but not yet materialized. pool.first() would throw
                // NoSuchElementException in that case.
                waitFor = pool.firstOrNull()
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
                // V-C2: factory() failed — bail out after 3 consecutive failures
                // so a permanently broken factory (corrupt model, OOM, bad delegate)
                // surfaces as an actionable exception instead of looping forever.
                if (created == null) {
                    if (++factoryFailures >= 3) {
                        throw RuntimeException(
                            "InterpreterPool.factory() failed $factoryFailures times for key=$key"
                        )
                    }
                    continue
                }
            }
            // If null, pool is empty (all slots reserved but not yet materialized) —
            // retry the loop to re-check for free slots.
            val chosen = waitFor ?: continue
            // Acquire exclusively with lock() (NOT withLock): the lease must KEEP
            // the mutex held until release(). withLock's finally block would unlock
            // on return, handing out an unheld interpreter → concurrent run() corruption.
            // Bound the wait (H-44): a stuck/leaked interpreter must not wedge this
            // caller forever. On timeout, retry the loop to re-evaluate free slots
            // or create a new interpreter.
            val locked = withTimeoutOrNull(30_000) { chosen.mutex.lock() }
            if (locked == null) {
                // V-H7: a stuck/leaked lease must not wedge this caller forever.
                // After 3 consecutive 30 s timeouts (90 s+) give up and throw so
                // the caller can surface the error instead of hanging indefinitely.
                if (++consecutiveTimeouts >= 3) {
                    throw RuntimeException(
                        "InterpreterPool lease stuck for ${consecutiveTimeouts * 30}s+ on key=$key"
                    )
                }
                continue
            }
            consecutiveTimeouts = 0
            return LeasedInterpreter(chosen, key, this)
        }
    }

    /** Release a leased interpreter back to the pool. */
    fun release(lease: LeasedInterpreter) {
        lease.pooled.mutex.unlock()
    }

    /** Close all interpreters + delegates in all pools. */
    suspend fun closeAll() {
        // V-M15: set closed BEFORE draining so a concurrent acquire() sees the
        // flag and throws IllegalStateException instead of repopulating the pool
        // with a brand-new interpreter that we then leak (never closed).
        closed = true
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
        // V-H6: AtomicBoolean (not plain `var`) so the read-modify-write is
        // atomic across threads — a plain boolean could be double-flipped by
        // two concurrent close() calls on different threads, double-unlocking
        // the mutex.
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) {
                pool.release(this)
            }
        }
    }

    companion object {
        private const val TAG = "InterpreterPool"
    }
}
