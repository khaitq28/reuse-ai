package com.trading.demo.service;

import com.trading.demo.market.OrderBook;
import com.trading.demo.model.Order;
import com.trading.demo.model.OrderStatus;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import com.trading.demo.risk.RiskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order Management System (OMS).
 *
 * Responsibilities:
 *   - Accept new orders from clients/strategies
 *   - Run pre-trade risk checks
 *   - Submit to the matching engine (OrderBook)
 *   - Track order state and positions
 *   - Notify listeners on fills and rejections
 *
 * This demo uses a simple synchronous model.
 * In production: event loop + Disruptor ring buffer for lock-free processing.
 */
public class OrderManagementSystem {

    private static final Logger log = LoggerFactory.getLogger(OrderManagementSystem.class);

    private final ConcurrentHashMap<Long, Order>   orders    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
    private final RiskEngine riskEngine = new RiskEngine();

    private ExecutionListener listener = (order, trades) -> {};

    public void setExecutionListener(ExecutionListener listener) {
        this.listener = listener;
    }

    /**
     * Submit a new order.
     * Returns the order (check status for REJECTED or active).
     */
    public Order submitOrder(Order order) {
        log.info("SUBMIT  {}", order);
        orders.put(order.getOrderId(), order);

        // Pre-trade risk check
        RiskEngine.RiskResult risk = riskEngine.check(order);
        if (!risk.isPassed()) {
            order.reject(risk.getRejectReason());
            log.warn("REJECTED orderId={} reason={}", order.getOrderId(), risk.getRejectReason());
            listener.onExecution(order, List.of());
            return order;
        }

        order.setStatus(OrderStatus.ACCEPTED);

        // Route to order book (matching engine)
        OrderBook book = books.computeIfAbsent(order.getSymbol(), OrderBook::new);
        List<Trade> trades = book.addOrder(order);

        // Update positions on fills
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

    public void updateMarketPrice(String symbol, double price) {
        riskEngine.updateMarketPrice(symbol, price);
    }

    public Order getOrder(long orderId) {
        return orders.get(orderId);
    }

    public OrderBook getBook(String symbol) {
        return books.get(symbol);
    }

    public long getPosition(String symbol) {
        return riskEngine.getPosition(symbol);
    }

    public void printAllBooks() {
        books.values().forEach(b -> b.printBook(5));
    }

    // --- Listener interface ---

    @FunctionalInterface
    public interface ExecutionListener {
        void onExecution(Order order, List<Trade> trades);
    }
}
