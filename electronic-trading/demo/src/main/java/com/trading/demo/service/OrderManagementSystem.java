package com.trading.demo.service;

import com.trading.demo.market.OrderBook;
import com.trading.demo.model.Order;
import com.trading.demo.model.OrderStatus;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import com.trading.demo.risk.RiskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order Management System (OMS) — Spring-managed singleton.
 *
 * Responsibilities:
 *   - Accept new orders from REST clients / strategies
 *   - Run pre-trade risk checks via {@link RiskEngine}
 *   - Route to the matching engine ({@link OrderBook})
 *   - Track live order state and net positions
 *   - Notify listeners on fills / rejections
 *
 * Thread-safety: ConcurrentHashMap for order/book storage;
 * RiskEngine uses AtomicLong for position and rate-limit counters.
 */
@Service
public class OrderManagementSystem {

    private static final Logger log = LoggerFactory.getLogger(OrderManagementSystem.class);

    private final RiskEngine riskEngine;

    private final ConcurrentHashMap<Long, Order>        orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderBook>  books  = new ConcurrentHashMap<>();

    private ExecutionListener listener = (order, trades) -> {};

    /** Spring constructor injection — RiskEngine is a @Component. */
    public OrderManagementSystem(RiskEngine riskEngine) {
        this.riskEngine = riskEngine;
    }

    public void setExecutionListener(ExecutionListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Core operations
    // ------------------------------------------------------------------

    /**
     * Submit a new order through risk checks and into the matching engine.
     * Returns the order with its updated status (ACCEPTED, PARTIALLY_FILLED, FILLED, or REJECTED).
     */
    public Order submitOrder(Order order) {
        log.info("SUBMIT  {}", order);
        orders.put(order.getOrderId(), order);

        // Pre-trade risk gate
        RiskEngine.RiskResult risk = riskEngine.check(order);
        if (!risk.isPassed()) {
            order.reject(risk.getRejectReason());
            log.warn("REJECTED orderId={} reason={}", order.getOrderId(), risk.getRejectReason());
            listener.onExecution(order, List.of());
            return order;
        }

        order.setStatus(OrderStatus.ACCEPTED);

        // Route to order book (CLOB matching engine)
        OrderBook book = books.computeIfAbsent(order.getSymbol(), OrderBook::new);
        List<Trade> trades = book.addOrder(order);

        // Update risk engine positions on each fill
        for (Trade t : trades) {
            riskEngine.updatePosition(order.getSymbol(), order.getSide(), t.getQuantity());
            log.info("TRADE   {}", t);
        }

        if (!trades.isEmpty() || order.getStatus() == OrderStatus.FILLED) {
            listener.onExecution(order, trades);
        }

        return order;
    }

    public boolean cancelOrder(long orderId) {
        Order order = orders.get(orderId);
        if (order == null || !order.isActive()) return false;

        OrderBook book = books.get(order.getSymbol());
        if (book != null) {
            book.cancelOrder(orderId, order.getSide());
        }
        order.cancel();
        log.info("CANCELLED orderId={}", orderId);
        return true;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public Order     getOrder(long orderId)   { return orders.get(orderId); }
    public OrderBook getBook(String symbol)   { return books.get(symbol); }
    public long      getPosition(String symbol) { return riskEngine.getPosition(symbol); }

    public void updateMarketPrice(String symbol, double price) {
        riskEngine.updateMarketPrice(symbol, price);
    }

    public void printAllBooks() {
        books.values().forEach(b -> b.printBook(5));
    }

    // ------------------------------------------------------------------
    // Listener
    // ------------------------------------------------------------------

    @FunctionalInterface
    public interface ExecutionListener {
        void onExecution(Order order, List<Trade> trades);
    }
}
