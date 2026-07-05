package com.trading.real.service;

import com.trading.demo.model.Order;
import com.trading.demo.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Async Write-Behind Audit Log.
 *
 * WHY: Persisting every order and trade to a database or file takes I/O time
 * (milliseconds). Doing this synchronously on the OMS hot path would add
 * milliseconds of latency to every order submission — unacceptable.
 *
 * Solution: The OMS thread writes events to an in-memory queue in ~100ns.
 * A separate dedicated consumer thread drains the queue and writes to storage
 * at its own pace, completely decoupled from the trading hot path.
 *
 * This pattern is called "write-behind" or "fire-and-forget":
 *   OMS thread   → offer(event) → [bounded queue] → consumer thread → storage
 *
 * Trade-offs:
 *   + Zero latency impact on OMS (queue offer ≈ 100ns)
 *   + Consumer can batch writes for efficiency
 *   - Small window of data loss if JVM crashes before queue is drained
 *   - Queue can fill up under extreme load (bounded queue = back-pressure)
 *
 * In production: replace the in-memory queue with Chronicle Queue
 * (persisted off-heap) for zero data loss AND zero GC impact.
 *
 * MiFID II context: all orders and trades MUST be logged with nanosecond
 * timestamps and stored for 5 years. This service simulates that obligation.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    // Bounded queue — if full, offer() returns false (OMS drops the event rather than block)
    // In production: use Chronicle Queue (persisted, off-heap) instead
    private static final int QUEUE_CAPACITY = 100_000;
    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private volatile boolean running = false;
    private Thread consumerThread;

    // -------------------------------------------------------------------------

    @PostConstruct
    public void start() {
        running = true;
        consumerThread = new Thread(this::consumeLoop, "audit-log-consumer");
        consumerThread.setDaemon(false);    // NOT daemon — drain the queue before JVM exits
        consumerThread.start();
        log.info("Audit log service started (queue capacity={})", QUEUE_CAPACITY);
    }

    @PreDestroy
    public void stop() {
        running = false;
        consumerThread.interrupt();
        try {
            consumerThread.join(5_000);     // wait up to 5s for queue to drain
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Audit log service stopped. Remaining in queue: {}", queue.size());
    }

    // -------------------------------------------------------------------------
    // Public API — called by OMS on the hot path (must be near-zero cost)
    // -------------------------------------------------------------------------

    /**
     * Log an order submission event. Non-blocking — returns in ~100ns.
     * If the queue is full, the event is dropped (logged as a warning).
     */
    public void logOrderSubmitted(Order order) {
        offer(new AuditEvent(AuditEvent.Type.ORDER_SUBMITTED, order, null));
    }

    public void logOrderFilled(Order order, List<Trade> trades) {
        offer(new AuditEvent(AuditEvent.Type.ORDER_FILLED, order, trades));
    }

    public void logOrderRejected(Order order, String reason) {
        offer(new AuditEvent(AuditEvent.Type.ORDER_REJECTED, order, null));
    }

    public void logOrderCancelled(Order order) {
        offer(new AuditEvent(AuditEvent.Type.ORDER_CANCELLED, order, null));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void offer(AuditEvent event) {
        // offer() is non-blocking — if queue full, drop with warning (never block OMS thread)
        if (!queue.offer(event)) {
            log.warn("AUDIT LOG QUEUE FULL — dropping event: {} orderId={}",
                    event.type, event.order.getClientOrderId());
        }
    }

    /** Consumer loop — runs on the dedicated audit-log-consumer thread */
    private void consumeLoop() {
        log.debug("Audit log consumer thread started");
        while (running || !queue.isEmpty()) {
            try {
                // poll with timeout so we periodically re-check the 'running' flag
                AuditEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    persist(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.debug("Audit log consumer thread stopped");
    }

    /**
     * Persist the event to durable storage.
     * In this demo: structured log to SLF4J.
     * In production: write to Chronicle Queue, Kafka, or a time-series DB.
     *
     * MiFID II requires nanosecond-precision timestamps, unique order IDs,
     * and 5-year retention. This method is the integration point for that.
     */
    private void persist(AuditEvent event) {
        log.info("AUDIT|{}|ts={}|orderId={}|symbol={}|side={}|qty={}|price={}|status={}",
                event.type,
                event.timestampNs,
                event.order.getClientOrderId(),
                event.order.getSymbol(),
                event.order.getSide(),
                event.order.getQuantity(),
                event.order.getPrice(),
                event.order.getStatus());

        if (event.trades != null) {
            for (Trade t : event.trades) {
                log.info("AUDIT|TRADE|buyOrder={}|sellOrder={}|symbol={}|qty={}|price={}",
                        t.getBuyOrderId(), t.getSellOrderId(),
                        t.getSymbol(), t.getQuantity(), t.getPrice());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audit event
    // -------------------------------------------------------------------------

    public static class AuditEvent {
        public enum Type { ORDER_SUBMITTED, ORDER_FILLED, ORDER_REJECTED, ORDER_CANCELLED }

        public final Type        type;
        public final Order       order;
        public final List<Trade> trades;
        public final long        timestampNs;   // nanosecond timestamp for MiFID II

        AuditEvent(Type type, Order order, List<Trade> trades) {
            this.type        = type;
            this.order       = order;
            this.trades      = trades;
            this.timestampNs = System.nanoTime();   // captured on OMS thread — not consumer thread
        }
    }

    // --- Monitoring ---
    public int getQueueSize()      { return queue.size(); }
    public int getQueueRemaining() { return queue.remainingCapacity(); }
}
