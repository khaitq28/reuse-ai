package com.trading.demo.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single trading order.
 *
 * Key design choices for production:
 * - Use primitives (double/long) instead of BigDecimal to avoid GC pressure
 * - Pre-allocate via object pool to avoid allocation on hot path
 * - volatile status for visibility across threads without locking
 */
public class Order {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long orderId;
    private final String clientOrderId;   // FIX tag 11
    private final String symbol;          // FIX tag 55 (e.g., "AAPL")
    private final Side side;             // FIX tag 54
    private final OrderType orderType;   // FIX tag 40
    private final double price;          // FIX tag 44 (0 for MARKET)
    private final long quantity;         // FIX tag 38

    private volatile OrderStatus status;
    private volatile long filledQuantity;
    private volatile double avgFillPrice;
    private final Instant createdAt;

    public Order(String clientOrderId, String symbol, Side side,
                 OrderType orderType, double price, long quantity) {
        this.orderId = ID_GENERATOR.getAndIncrement();
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.status = OrderStatus.NEW;
        this.filledQuantity = 0;
        this.avgFillPrice = 0.0;
        this.createdAt = Instant.now();
    }

    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isActive() {
        return status == OrderStatus.NEW
                || status == OrderStatus.PENDING_NEW
                || status == OrderStatus.ACCEPTED
                || status == OrderStatus.PARTIALLY_FILLED;
    }

    // --- Fill logic ---

    public void applyFill(long fillQty, double fillPrice) {
        long newFilled = filledQuantity + fillQty;
        // Update average fill price
        this.avgFillPrice = (avgFillPrice * filledQuantity + fillPrice * fillQty) / newFilled;
        this.filledQuantity = newFilled;
        this.status = (newFilled >= quantity) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (isActive()) this.status = OrderStatus.CANCELLED;
    }

    public void reject(String reason) {
        this.status = OrderStatus.REJECTED;
    }

    // --- Getters ---
    public long getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public double getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public long getFilledQuantity() { return filledQuantity; }
    public double getAvgFillPrice() { return avgFillPrice; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return String.format("Order{id=%d, clOrdId=%s, %s %s %d %s @ %.2f, status=%s, filled=%d/%.2f}",
                orderId, clientOrderId, side, symbol, quantity, orderType,
                price, status, filledQuantity, avgFillPrice);
    }
}
