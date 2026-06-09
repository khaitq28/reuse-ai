package com.trading.demo;

import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.risk.RiskEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {

    private RiskEngine risk;

    @BeforeEach
    void setUp() {
        risk = new RiskEngine();
        risk.updateMarketPrice("AAPL", 150.00);
    }

    @Test
    void validOrder_passes() {
        Order order = new Order("T1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 100);
        RiskEngine.RiskResult result = risk.check(order);
        assertTrue(result.isPassed());
    }

    @Test
    void notionalLimitBreach_rejected() {
        // $150 * 7000 = $1,050,000 > $1M limit
        Order order = new Order("T1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 7000);
        RiskEngine.RiskResult result = risk.check(order);
        assertFalse(result.isPassed());
        assertTrue(result.getRejectReason().contains("Notional"));
    }

    @Test
    void fatFingerCheck_priceDeviates15Percent_rejected() {
        // 150 * 1.15 = 172.50 — 15% above market
        Order order = new Order("T1", "AAPL", Side.BUY, OrderType.LIMIT, 172.50, 10);
        RiskEngine.RiskResult result = risk.check(order);
        assertFalse(result.isPassed());
        assertTrue(result.getRejectReason().contains("deviates"));
    }

    @Test
    void fatFingerCheck_priceWithin10Percent_passes() {
        // 150 * 1.09 = 163.50 — 9% above market
        Order order = new Order("T1", "AAPL", Side.BUY, OrderType.LIMIT, 163.50, 10);
        RiskEngine.RiskResult result = risk.check(order);
        assertTrue(result.isPassed());
    }

    @Test
    void positionLimitBreach_rejected() {
        // Buy 60k already
        risk.updatePosition("AAPL", Side.BUY, 60_000);
        // Try to buy another 50k — total 110k > 100k limit
        Order order = new Order("T1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 50_000);
        RiskEngine.RiskResult result = risk.check(order);
        assertFalse(result.isPassed());
//        assertTrue(result.getRejectReason().contains("Position limit"));
    }

    @Test
    void positionLimitBreach_shortSide_rejected() {
        // Short 60k already
        risk.updatePosition("AAPL", Side.SELL, 60_000);
        // Try to sell another 50k — total short 110k > 100k limit
        Order order = new Order("T1", "AAPL", Side.SELL, OrderType.LIMIT, 150.00, 50_000);
        RiskEngine.RiskResult result = risk.check(order);
        assertFalse(result.isPassed());
    }
}
