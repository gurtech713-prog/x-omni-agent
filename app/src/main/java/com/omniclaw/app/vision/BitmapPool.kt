package com.omniclaw.app.vision

import android.graphics.Bitmap
import android.util.Log
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bounded pool of reusable [Bitmap] objects for image preprocessing.
 *
 * Allocating a new Bitmap for every VLM request is expensive: a 1080p
 * ARGB_8888 bitmap is ~8MB, and the GC churn from per-request allocation
 * causes visible jank during continuous screen-capture pipelines.
 *
 * This pool maintains a small ring of pre-allocated bitmaps (default 4)
 * and lends them out via [acquire]. Callers must [release] the bitmap
 * when done so it returns to the pool for reuse. If the pool is empty,
 * [acquire] allocates a new bitmap on demand (up to [maxPoolSize]).
 *
 * Thread safety: the internal deque is concurrent; [acquire] / [release]
 * are safe to call from any thread. The [allocated] counter is an
 * [AtomicInteger] so it stays consistent under concurrent release.
 *
 * Note: the bitmaps in the pool have a FIXED size (the screen resolution
 * at pool construction). Callers that need a different size should call
 * [Bitmap.createScaledBitmap] on the acquired bitmap and release the
 * intermediate.
 */
class BitmapPool(
    private val width: Int,
    private val height: Int,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    private val maxPoolSize: Int = 4,
) {
    private val pool = ConcurrentLinkedDeque<Bitmap>()
    private val allocated = AtomicInteger(0)

    // Identity-tracked set of bitmaps currently loaned out via [acquire].
    // release() only touches the allocation counter for bitmaps present here,
    // so releasing a foreign (never-acquired) bitmap cannot corrupt the count.
    private val loaned: MutableSet<Bitmap> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>()))

    /**
     * Acquire a bitmap from the pool, or allocate a new one if the pool is
     * empty and below [maxPoolSize]. Returns null if the pool is exhausted
     * (all bitmaps in use and at capacity).
     *
     * The returned bitmap's pixels are NOT cleared — callers must overwrite
     * the entire bitmap before use.
     */
    fun acquire(): Bitmap? {
        pool.pollFirst()?.let {
            loaned.add(it)
            return it
        }
        // Atomically check-and-increment the allocation counter. If we exceed
        // maxPoolSize, no new bitmap is allocated.
        while (true) {
            val cur = allocated.get()
            if (cur >= maxPoolSize) return null
            if (allocated.compareAndSet(cur, cur + 1)) {
                return runCatching {
                    Bitmap.createBitmap(width, height, config)
                }.getOrElse {
                    // Allocation failed (OOM) — undo the increment.
                    allocated.decrementAndGet()
                    null
                }?.also { loaned.add(it) }
            }
        }
    }

    /**
     * Return [bitmap] to the pool for reuse.
     *
     * Only bitmaps that were actually acquired from this pool are tracked:
     * releasing a foreign bitmap is a no-op and never touches the allocation
     * counter. If a loaned bitmap has the wrong shape or has been recycled
     * externally, it is discarded and the allocation counter is decremented
     * so a future [acquire] can allocate a replacement.
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null) return
        // Ignore bitmaps that were never acquired from this pool — otherwise
        // the counter would go negative and the pool could grow unbounded.
        if (!loaned.remove(bitmap)) return
        if (bitmap.width != width || bitmap.height != height || bitmap.config != config) {
            runCatching { bitmap.recycle() }
            allocated.decrementAndGet()
            return
        }
        if (bitmap.isRecycled) {
            // Already recycled externally — don't pollute the pool.
            allocated.decrementAndGet()
            return
        }
        pool.addFirst(bitmap)
    }

    /**
     * Recycle all pooled (not-currently-loaned) bitmaps.
     *
     * The allocation counter is decremented for each recycled pooled bitmap
     * but is NOT reset to zero: bitmaps that are still loaned out remain live
     * and stay counted. As those outstanding bitmaps are released, [release]
     * keeps the counter consistent. Resetting to zero here would let [acquire]
     * allocate up to [maxPoolSize] additional bitmaps on top of the live ones.
     */
    fun clear() {
        pool.forEach {
            runCatching { it.recycle() }
            allocated.decrementAndGet()
        }
        pool.clear()
    }

    /** Current number of bitmaps available in the pool (not in use). */
    val available: Int get() = pool.size

    companion object {
        private const val TAG = "BitmapPool"
    }
}
