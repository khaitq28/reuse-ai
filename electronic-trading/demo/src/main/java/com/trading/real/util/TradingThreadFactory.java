package com.trading.real.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named Thread Factory for trading system threads.
 *
 * WHY: When a thread dump is taken during a production incident (latency spike,
 * deadlock, high CPU), threads named "pool-3-thread-7" are useless.
 * Threads named "order-processor-1" or "market-data-consumer" tell you
 * instantly which component is stuck.
 *
 * Usage:
 *   ExecutorService orderPool = Executors.newFixedThreadPool(4,
 *       new TradingThreadFactory("order-processor", false));
 *
 *   ExecutorService daemonPool = Executors.newCachedThreadPool(
 *       new TradingThreadFactory("market-data-handler", true));
 *
 * Daemon vs non-daemon:
 *   - daemon=true:  thread does NOT prevent JVM shutdown (use for background helpers)
 *   - daemon=false: thread BLOCKS JVM shutdown until it finishes (use for order/audit threads
 *                   so they drain gracefully before the process exits)
 *
 * In production: also set thread priority and optionally CPU affinity (via
 * java-thread-affinity library) to pin latency-sensitive threads to dedicated cores.
 */
public class TradingThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(TradingThreadFactory.class);

    private final String        namePrefix;
    private final boolean       daemon;
    private final int           priority;
    private final AtomicInteger counter = new AtomicInteger(1);

    /**
     * @param namePrefix  thread name prefix (e.g., "order-processor", "risk-worker")
     * @param daemon      true = daemon thread (does not prevent JVM shutdown)
     */
    public TradingThreadFactory(String namePrefix, boolean daemon) {
        this(namePrefix, daemon, Thread.NORM_PRIORITY);
    }

    /**
     * @param namePrefix  thread name prefix
     * @param daemon      daemon flag
     * @param priority    thread priority (Thread.MIN_PRIORITY=1 to MAX_PRIORITY=10)
     *                    For trading hot path: Thread.MAX_PRIORITY (10)
     *                    For background workers: Thread.NORM_PRIORITY (5)
     */
    public TradingThreadFactory(String namePrefix, boolean daemon, int priority) {
        this.namePrefix = namePrefix;
        this.daemon     = daemon;
        this.priority   = priority;
    }

    @Override
    public Thread newThread(Runnable r) {
        String name = namePrefix + "-" + counter.getAndIncrement();
        Thread t = new Thread(r, name);
        t.setDaemon(daemon);
        t.setPriority(priority);

        // Uncaught exception handler — log the error and alert operations
        // (a silent thread death in a trading system can leave orders unmanaged)
        t.setUncaughtExceptionHandler((thread, ex) ->
            log.error("UNCAUGHT EXCEPTION in thread '{}' — investigate immediately: {}",
                    thread.getName(), ex.getMessage(), ex)
        );

        log.debug("Created thread: {} (daemon={}, priority={})", name, daemon, priority);
        return t;
    }

    // --- Convenience factories for common trading thread types ---

    /** Hot path thread: non-daemon, max priority */
    public static TradingThreadFactory hotPath(String name) {
        return new TradingThreadFactory(name, false, Thread.MAX_PRIORITY);
    }

    /** Background worker: daemon, normal priority */
    public static TradingThreadFactory background(String name) {
        return new TradingThreadFactory(name, true, Thread.NORM_PRIORITY);
    }

    /** Market data thread: daemon, high priority */
    public static TradingThreadFactory marketData(String name) {
        return new TradingThreadFactory(name, true, Thread.MAX_PRIORITY - 1);
    }
}
