package com.trading.real.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Generic lock-free object pool — avoid new object allocation on the hot path.
 *
 * WHY: In a trading system, creating a new Order/Trade object on every tick
 * puts constant pressure on Young Gen → frequent Minor GC → latency spikes.
 * A pool pre-allocates N objects at startup and reuses them in a ring.
 *
 * Design:
 *   - Fixed-size circular array — no resize, no GC
 *   - AtomicInteger for the borrow index — CAS, no locking
 *   - Objects are RESET before return, not recreated
 *
 * Trade-off vs production:
 *   - This is a simple "always-borrow, never return" ring — correct for
 *     a single-threaded event loop where each slot is processed before the
 *     ring wraps. In multi-threaded use, pair with a proper return queue.
 *
 * Production alternative: Agrona's ManyToManyConcurrentArrayQueue,
 * or Chronicle Pool for off-heap pooling.
 */
public class ObjectPool<T> {

    private final Object[] pool;
    private final int      mask;       // pool.length - 1 (power of 2 for bitwise modulo)
    private final AtomicInteger index = new AtomicInteger(0);

    /**
     * @param size     pool capacity — MUST be a power of 2
     * @param factory  creates each pre-allocated instance (called once per slot at startup)
     */
    @SuppressWarnings("unchecked")
    public ObjectPool(int size, Supplier<T> factory) {
        if (Integer.bitCount(size) != 1)
            throw new IllegalArgumentException("Pool size must be a power of 2, got: " + size);
        this.pool = new Object[size];
        this.mask = size - 1;
        for (int i = 0; i < size; i++) {
            pool[i] = factory.get();   // pre-allocate ALL objects at startup — zero GC at runtime
        }
    }

    /**
     * Borrow the next slot in the ring.
     * The caller MUST reset the object's state before use.
     * Thread-safe via CAS on the index.
     */
    @SuppressWarnings("unchecked")
    public T borrow() {
        // incrementAndGet returns next index; & mask gives position in ring (no modulo needed)
        int slot = index.incrementAndGet() & mask;
        return (T) pool[slot];
    }

    public int capacity() { return pool.length; }
}
