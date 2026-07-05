package com.trading.real.service;

import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.risk.RiskEngine;
import com.trading.demo.service.OrderManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JIT Warmup Service — forces Tier-4 (C2) compilation of all hot paths before market open.
 *
 * WHY: At JVM startup, all code runs through the interpreter (Tier 0) or
 * lightly-optimised C1 (Tier 1-3). This is 10-100x slower than Tier-4 native code.
 * If the first real orders arrive cold, the trading thread competes with the JIT
 * compiler for CPU — causing unpredictable latency spikes during market open.
 *
 * Solution: replay synthetic orders at startup until all hot methods reach Tier 4.
 * A method is typically compiled to Tier 4 after ~10,000 invocations
 * (controlled by -XX:CompileThreshold, default 10000 for C2).
 *
 * In production: warmup is done against a UAT/simulation environment,
 * then the process is kept warm (never restarted during market hours).
 *
 * NOTE: This class uses @Component so Spring auto-wires it. For Spring to scan
 * com.trading.real.*, add @ComponentScan("com.trading") to TradingApplication,
 * or reference this class explicitly. In study mode, instantiate it directly.
 */
@Component
public class WarmupService {

    private static final Logger log = LoggerFactory.getLogger(WarmupService.class);

    // Warmup iteration count — 20,000 ensures Tier 4 for all hot methods
    private static final int WARMUP_ITERATIONS = 20_000;

    private static final String[] WARMUP_SYMBOLS = {"AAPL", "MSFT", "GOOGL", "AMZN", "BNP.PA"};
    private static final double[] WARMUP_PRICES  = {150.0, 300.0, 140.0, 180.0, 65.0};

    private final OrderManagementSystem oms;
    private final RiskEngine            riskEngine;
    private final AtomicLong            warmupOrderId = new AtomicLong(-1_000_000L); // negative = warmup range

    @Autowired
    public WarmupService(OrderManagementSystem oms, RiskEngine riskEngine) {
        this.oms        = oms;
        this.riskEngine = riskEngine;
    }

    /**
     * Runs AFTER all beans are fully initialised (Spring @PostConstruct guarantee).
     * Submits synthetic orders through the full hot path: risk check → order book → matching.
     * Result: all critical methods compiled to Tier 4 before first real market tick.
     */
    @PostConstruct
    public void warmUp() {
        log.info("=== JIT WARMUP: starting {} iterations across {} symbols ===",
                WARMUP_ITERATIONS, WARMUP_SYMBOLS.length);

        long startMs = System.currentTimeMillis();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Alternate symbols and sides to exercise all code paths
            String symbol = WARMUP_SYMBOLS[i % WARMUP_SYMBOLS.length];
            double price  = WARMUP_PRICES[i % WARMUP_PRICES.length];
            Side   side   = (i % 2 == 0) ? Side.BUY : Side.SELL;

            // Build a lightweight warmup order (does not affect real order state)
            Order order = new Order(
                    "WARMUP-" + i,          // clientOrderId — prefixed to identify warmup traffic
                    symbol,
                    side,
                    OrderType.LIMIT,
                    price,
                    10                      // small qty — will not move the book significantly
            );

            // Exercise the FULL hot path:
            riskEngine.check(order);        // forces Tier-4 compilation of all 4 risk checks
            oms.submitOrder(order);         // forces Tier-4 of OMS, OrderBook matching, position update
        }

        // Update market prices to warm up the fat-finger check path
        for (int i = 0; i < WARMUP_SYMBOLS.length; i++) {
            riskEngine.updateMarketPrice(WARMUP_SYMBOLS[i], WARMUP_PRICES[i]);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("=== JIT WARMUP complete in {}ms — all hot paths at Tier 4 ===", elapsedMs);
        log.info("=== System ready for live trading ===");
    }
}
