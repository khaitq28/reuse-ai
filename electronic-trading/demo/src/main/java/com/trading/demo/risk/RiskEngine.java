package com.trading.demo.risk;

import com.trading.demo.model.Order;
import com.trading.demo.model.Side;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-trade risk engine — validates orders before sending to exchange.
 *
 * In production HFT, all checks must complete in < 1 microsecond.
 * Each check is independent and fail-fast ordered from cheapest to most expensive.
 */
public class RiskEngine {

    private static final double MAX_NOTIONAL_PER_ORDER = 1_000_000.0;   // $1M per order
    private static final long MAX_POSITION_PER_SYMBOL  = 100_000L;       // 100k shares
    private static final double MAX_PRICE_DEVIATION     = 0.10;           // 10% fat finger
    private static final long MAX_ORDERS_PER_SECOND     = 100;

    // Track net position per symbol: positive = long, negative = short
    private final ConcurrentHashMap<String, AtomicLong> positions = new ConcurrentHashMap<>();

    // Order rate limiting
    private volatile long windowStart = System.currentTimeMillis();
    private final AtomicLong ordersInWindow = new AtomicLong(0);

    // Last known market price per symbol (fed from market data)
    private final ConcurrentHashMap<String, Double> marketPrices = new ConcurrentHashMap<>();

    public RiskResult check(Order order) {
        // 1. Notional limit (fat dollar check)
        if (order.getPrice() > 0) {
            double notional = order.getPrice() * order.getQuantity();
            if (notional > MAX_NOTIONAL_PER_ORDER) {
                return RiskResult.reject(String.format(
                        "Notional %.0f exceeds limit %.0f", notional, MAX_NOTIONAL_PER_ORDER));
            }
        }

        // 2. Fat finger price check
        Double marketPrice = marketPrices.get(order.getSymbol());
        if (marketPrice != null && order.getPrice() > 0) {
            double deviation = Math.abs(order.getPrice() - marketPrice) / marketPrice;
            if (deviation > MAX_PRICE_DEVIATION) {
                return RiskResult.reject(String.format(
                        "Price %.2f deviates %.1f%% from market %.2f (limit %.1f%%)",
                        order.getPrice(), deviation * 100, marketPrice, MAX_PRICE_DEVIATION * 100));
            }
        }

        // 3. Position limit check
        long currentPosition = getPosition(order.getSymbol());
        long positionDelta = (order.getSide() == Side.BUY)
                ? order.getQuantity() : -order.getQuantity();
        long newPosition = currentPosition + positionDelta;

        if (Math.abs(newPosition) > MAX_POSITION_PER_SYMBOL) {
            return RiskResult.reject(String.format(
                    "Position limit breached: current=%d, delta=%d, limit=%d",
                    currentPosition, positionDelta, MAX_POSITION_PER_SYMBOL));
        }

        // 4. Order rate limit
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) {
            windowStart = now;
            ordersInWindow.set(0);
        }
        if (ordersInWindow.incrementAndGet() > MAX_ORDERS_PER_SECOND) {
            return RiskResult.reject("Order rate limit exceeded: " + MAX_ORDERS_PER_SECOND + "/sec");
        }

        return RiskResult.PASSED;
    }

    public void updatePosition(String symbol, Side side, long filledQty) {
        AtomicLong pos = positions.computeIfAbsent(symbol, k -> new AtomicLong(0));
        if (side == Side.BUY) {
            pos.addAndGet(filledQty);
        } else {
            pos.addAndGet(-filledQty);
        }
    }

    public void updateMarketPrice(String symbol, double price) {
        marketPrices.put(symbol, price);
    }

    public long getPosition(String symbol) {
        AtomicLong pos = positions.get(symbol);
        return pos == null ? 0 : pos.get();
    }

    // --- Risk Result ---

    public static class RiskResult {
        public static final RiskResult PASSED = new RiskResult(true, null);

        private final boolean passed;
        private final String rejectReason;

        private RiskResult(boolean passed, String rejectReason) {
            this.passed = passed;
            this.rejectReason = rejectReason;
        }

        public static RiskResult reject(String reason) {
            return new RiskResult(false, reason);
        }

        public boolean isPassed() { return passed; }
        public String getRejectReason() { return rejectReason; }
    }
}
