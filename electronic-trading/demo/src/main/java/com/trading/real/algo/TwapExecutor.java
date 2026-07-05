package com.trading.real.algo;

import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.service.OrderManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TWAP (Time-Weighted Average Price) Execution Algorithm.
 *
 * PURPOSE: A trader wants to buy 100,000 shares of AAPL without moving the market.
 * Sending one order for 100,000 shares would consume all available liquidity and
 * push the price up (market impact). Instead, TWAP slices it into N equal child
 * orders sent at regular intervals over a defined time window.
 *
 * HOW IT WORKS:
 *   Parent order: BUY 100,000 AAPL over 60 minutes
 *   → 60 child orders of 1,667 shares, one per minute
 *   → Each child is sent as a limit order near the current mid-price
 *   → Total execution ≈ time-weighted average of the market price
 *
 * WHY Java developers build this:
 *   - Core product of every sell-side bank's EMS (Execution Management System)
 *   - French banks (SocGen, BNP, Natixis) all have TWAP/VWAP algos
 *   - Uses ScheduledExecutorService — fundamental Java concurrency primitive
 *
 * This implementation:
 *   - Splits quantity into equal slices (last slice absorbs rounding remainder)
 *   - Uses a ScheduledExecutorService for fixed-rate dispatch
 *   - Tracks sent / remaining quantities atomically
 *   - Provides cancel() to stop mid-execution
 *   - Exposes progress metrics
 *
 * Production extensions (not implemented here):
 *   - Dynamic price limit (track mid-price and adjust each slice's limit)
 *   - Participation rate cap (don't exceed X% of market volume)
 *   - IOC (Immediate or Cancel) child orders to avoid residual resting
 *   - Randomise slice timing slightly (±10%) to avoid detection by predatory algos
 */
public class TwapExecutor {

    private static final Logger log = LoggerFactory.getLogger(TwapExecutor.class);

    private final OrderManagementSystem    oms;
    private final ScheduledExecutorService scheduler;

    // --- Parent order parameters ---
    private final String symbol;
    private final Side   side;
    private final long   totalQty;
    private final double limitPrice;    // worst acceptable price for each child
    private final int    numSlices;
    private final long   intervalMs;    // milliseconds between child orders

    // --- State (thread-safe — scheduler thread writes, main thread reads) ---
    private final AtomicLong    sentQty    = new AtomicLong(0);
    private final AtomicInteger slicesSent = new AtomicInteger(0);

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean            cancelled = false;

    private static final ThreadFactory TWAP_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "twap-scheduler");
        t.setDaemon(true);      // daemon: does not prevent JVM shutdown
        return t;
    };

    /**
     * @param oms        OMS to route child orders through
     * @param symbol     instrument to trade (e.g., "AAPL", "BNP.PA")
     * @param side       BUY or SELL
     * @param totalQty   total parent quantity to execute
     * @param limitPrice worst acceptable limit price per child order
     * @param numSlices  how many child orders to split into
     * @param durationMs total execution window in milliseconds
     */
    public TwapExecutor(OrderManagementSystem oms,
                        String symbol, Side side,
                        long totalQty, double limitPrice,
                        int numSlices, long durationMs) {
        this.oms        = oms;
        this.symbol     = symbol;
        this.side       = side;
        this.totalQty   = totalQty;
        this.limitPrice = limitPrice;
        this.numSlices  = numSlices;
        this.intervalMs = durationMs / numSlices;
        this.scheduler  = Executors.newSingleThreadScheduledExecutor(TWAP_THREAD_FACTORY);

        log.info("TWAP created: {} {} {} @ {} | {} slices every {}ms ({}ms total)",
                side, totalQty, symbol, limitPrice, numSlices, intervalMs, durationMs);
    }

    /**
     * Start TWAP execution. Child orders are dispatched at fixed intervals.
     * Non-blocking — returns immediately; execution runs on the scheduler thread.
     *
     * scheduleAtFixedRate vs scheduleWithFixedDelay:
     *   - scheduleAtFixedRate: fires at wall-clock intervals regardless of task duration
     *                          → correct for TWAP (we want slices at fixed market times)
     *   - scheduleWithFixedDelay: waits for task to finish, then starts the delay
     *                              → correct for polling (retry after last attempt completes)
     */
    public void start() {
        long baseSliceQty = totalQty / numSlices;    // quantity per regular slice
        long remainderQty = totalQty % numSlices;    // leftover absorbed by last slice

        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            if (cancelled) return;

            int sliceNum = slicesSent.incrementAndGet();
            if (sliceNum > numSlices) {
                cancel();
                return;
            }

            // Last slice absorbs any rounding remainder
            long sliceQty = (sliceNum == numSlices)
                    ? baseSliceQty + remainderQty
                    : baseSliceQty;

            String childId = String.format("TWAP-%s-%s-%d-of-%d",
                    symbol, side, sliceNum, numSlices);

            Order child = new Order(childId, symbol, side, OrderType.LIMIT, limitPrice, sliceQty);
            oms.submitOrder(child);
            sentQty.addAndGet(sliceQty);

            log.debug("TWAP slice {}/{}: {} {} {} @ {}  (sent={} / total={})",
                    sliceNum, numSlices, side, sliceQty, symbol, limitPrice,
                    sentQty.get(), totalQty);

        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.info("TWAP started: {} {} {}", side, totalQty, symbol);
    }

    /**
     * Cancel the TWAP. Stops dispatching new child orders.
     * Already-sent child orders remain active in the OMS.
     */
    public void cancel() {
        cancelled = true;
        if (scheduledTask != null) scheduledTask.cancel(false);
        scheduler.shutdown();
        log.info("TWAP cancelled: {}/{} qty sent ({} slices of {})",
                sentQty.get(), totalQty, slicesSent.get(), numSlices);
    }

    // --- Monitoring ---
    public long    getSentQty()      { return sentQty.get(); }
    public long    getRemainingQty() { return totalQty - sentQty.get(); }
    public int     getSlicesSent()   { return slicesSent.get(); }
    public boolean isComplete()      { return slicesSent.get() >= numSlices || cancelled; }
    public double  getPctComplete()  { return (double) sentQty.get() / totalQty * 100; }
}
