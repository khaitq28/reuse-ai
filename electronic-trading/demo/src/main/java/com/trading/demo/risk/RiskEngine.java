package com.trading.demo.risk;

import com.trading.demo.config.RiskProperties;
import com.trading.demo.model.Order;
import com.trading.demo.model.Side;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-trade risk engine — validates orders before sending to the matching engine.
 *
 * All four checks are ordered cheapest → most expensive (fail-fast).
 * In production HFT each check must complete in < 1 µs.
 *
 * Risk limits are externalised via {@link RiskProperties} (application.yml).
 * In a real bank these are stored in a risk DB and hot-reloaded without restart.
 */
@Component
public class RiskEngine {

    private final double maxNotionalPerOrder;
    private final long   maxPositionPerSymbol;
    private final double maxPriceDeviationPct;
    private final long   maxOrdersPerSecond;

    // Net position per symbol: positive = long, negative = short
    private final ConcurrentHashMap<String, AtomicLong> positions    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double>     marketPrices = new ConcurrentHashMap<>();

    // Rate-limit window
    private volatile long windowStart  = System.currentTimeMillis();
    private final AtomicLong ordersInWindow = new AtomicLong(0);

    /** Spring constructor — limits come from application.yml via RiskProperties. */
    @org.springframework.beans.factory.annotation.Autowired
    public RiskEngine(RiskProperties props) {
        this.maxNotionalPerOrder  = props.getMaxNotionalPerOrder();
        this.maxPositionPerSymbol = props.getMaxPositionPerSymbol();
        this.maxPriceDeviationPct = props.getMaxPriceDeviationPct();
        this.maxOrdersPerSecond   = props.getMaxOrdersPerSecond();
    }

    /** No-arg constructor for unit tests (uses same defaults as application.yml). */
    public RiskEngine() {
        this(new RiskProperties());
    }

    // ------------------------------------------------------------------
    // Risk checks
    // ------------------------------------------------------------------

    public RiskResult check(Order order) {

        // 1. Notional limit (fat-dollar check) — cheapest: single multiply
        if (order.getPrice() > 0) {
            double notional = order.getPrice() * order.getQuantity();
            if (notional > maxNotionalPerOrder) {
                return RiskResult.reject(String.format(
                        "Notional %.0f exceeds limit %.0f", notional, maxNotionalPerOrder));
            }
        }

        // 2. Fat-finger price check
        Double marketPrice = marketPrices.get(order.getSymbol());
        if (marketPrice != null && order.getPrice() > 0) {
            double deviation = Math.abs(order.getPrice() - marketPrice) / marketPrice;
            if (deviation > maxPriceDeviationPct) {
                return RiskResult.reject(String.format(
                        "Price %.2f deviates %.1f%% from market %.2f (limit %.1f%%)",
                        order.getPrice(), deviation * 100, marketPrice, maxPriceDeviationPct * 100));
            }
        }

        // 3. Position limit
        long currentPosition = getPosition(order.getSymbol());
        long delta     = (order.getSide() == Side.BUY) ? order.getQuantity() : -order.getQuantity();
        long newPosition = currentPosition + delta;
        if (Math.abs(newPosition) > maxPositionPerSymbol) {
            return RiskResult.reject(String.format(
                    "Position limit breached: current=%d, delta=%d, limit=%d",
                    currentPosition, delta, maxPositionPerSymbol));
        }

        // 4. Order rate limit
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) {
            windowStart = now;
            ordersInWindow.set(0);
        }
        if (ordersInWindow.incrementAndGet() > maxOrdersPerSecond) {
            return RiskResult.reject("Order rate limit exceeded: " + maxOrdersPerSecond + "/sec");
        }

        return RiskResult.PASSED;
    }

    // ------------------------------------------------------------------
    // State updates
    // ------------------------------------------------------------------

    public void updatePosition(String symbol, Side side, long filledQty) {
        AtomicLong pos = positions.computeIfAbsent(symbol, k -> new AtomicLong(0));
        pos.addAndGet(side == Side.BUY ? filledQty : -filledQty);
    }

    public void updateMarketPrice(String symbol, double price) {
        marketPrices.put(symbol, price);
    }

    public long getPosition(String symbol) {
        AtomicLong pos = positions.get(symbol);
        return pos == null ? 0 : pos.get();
    }

    // ------------------------------------------------------------------
    // Result type
    // ------------------------------------------------------------------

    public static class RiskResult {
        public static final RiskResult PASSED = new RiskResult(true, null);

        private final boolean passed;
        private final String  rejectReason;

        private RiskResult(boolean passed, String rejectReason) {
            this.passed = passed;
            this.rejectReason = rejectReason;
        }

        public static RiskResult reject(String reason) {
            return new RiskResult(false, reason);
        }

        public boolean isPassed()        { return passed; }
        public String  getRejectReason() { return rejectReason; }
    }
}
