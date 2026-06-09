package com.trading.demo.engine;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.trading.demo.model.Order;
import com.trading.demo.service.OrderManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the LMAX Disruptor pattern for ultra-fast order processing.
 *
 * The Disruptor replaces a standard BlockingQueue with a ring buffer:
 *   - Pre-allocated event objects (no GC pressure)
 *   - Lock-free sequence tracking via CAS
 *   - Single writer / multiple reader pattern
 *   - Cacheline-padded sequences to avoid false sharing
 *
 * Architecture:
 *   OrderPublisher → [RingBuffer] → RiskHandler → OMSHandler
 *
 * In production systems (e.g., LMAX Exchange), this pattern processes
 * 6+ million events/second with consistent sub-microsecond latency.
 */
public class DisruptorOrderPipeline {

    private static final Logger log = LoggerFactory.getLogger(DisruptorOrderPipeline.class);

    // Ring buffer size — MUST be a power of 2 for bitwise modulo
    private static final int BUFFER_SIZE = 1024;

    // --- Event (pre-allocated, reused) ---
    public static class OrderEvent {
        public Order order;

        public void set(Order order) {
            this.order = order;
        }
    }

    // --- Factory — creates pre-allocated events ---
    public static class OrderEventFactory implements EventFactory<OrderEvent> {
        @Override
        public OrderEvent newInstance() {
            return new OrderEvent();
        }
    }

    // --- Translator — populates an event slot from publisher data ---
    public static class OrderEventTranslator implements EventTranslatorOneArg<OrderEvent, Order> {
        @Override
        public void translateTo(OrderEvent event, long sequence, Order order) {
            event.set(order);
        }
    }

    // --- Risk Handler ---
    public static class RiskCheckHandler implements EventHandler<OrderEvent> {
        @Override
        public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
            // In real system: run pre-trade risk checks here
            log.debug("Risk check seq={} order={}", sequence, event.order.getClientOrderId());
        }
    }

    // --- OMS Handler ---
    public static class OMSHandler implements EventHandler<OrderEvent> {
        private final OrderManagementSystem oms;

        public OMSHandler(OrderManagementSystem oms) {
            this.oms = oms;
        }

        @Override
        public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
            oms.submitOrder(event.order);
        }
    }

    // --- Pipeline ---

    private final Disruptor<OrderEvent> disruptor;
    private final RingBuffer<OrderEvent> ringBuffer;
    private final OrderEventTranslator translator = new OrderEventTranslator();

    public DisruptorOrderPipeline(OrderManagementSystem oms) {
        disruptor = new Disruptor<>(
                new OrderEventFactory(),
                BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,       // Single producer — faster (no CAS on publish)
                new BusySpinWaitStrategy() // Spin wait — lowest latency, burns CPU
        );

        // Chain handlers: risk check → then OMS processing
        disruptor
                .handleEventsWith(new RiskCheckHandler())
                .then(new OMSHandler(oms));

        ringBuffer = disruptor.start();
        log.info("Disruptor pipeline started, buffer size={}", BUFFER_SIZE);
    }

    /**
     * Publish an order to the ring buffer (non-blocking).
     * The handlers process it asynchronously in sequence.
     */
    public void publish(Order order) {
        ringBuffer.publishEvent(translator, order);
    }

    public void shutdown() {
        try {
            disruptor.shutdown(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            disruptor.halt();
        }
    }
}
