package com.trading.demo;

import com.trading.demo.engine.DisruptorOrderPipeline;
import com.trading.demo.engine.MarketMaker;
import com.trading.demo.market.OrderBook;
import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import com.trading.demo.service.OrderManagementSystem;
import com.trading.demo.util.LatencyTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Main demo — illustrates core electronic trading concepts with Java.
 *
 * Scenarios demonstrated:
 *   1. Basic order lifecycle (limit orders, market orders)
 *   2. Order book matching (price-time priority)
 *   3. Pre-trade risk checks
 *   4. Market making strategy
 *   5. LMAX Disruptor pipeline
 *   6. Latency measurement
 */
public class TradingSystemDemo {

    private static final Logger log = LoggerFactory.getLogger(TradingSystemDemo.class);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("==========================================================");
        System.out.println("  ELECTRONIC TRADING DEMO — Java");
        System.out.println("==========================================================\n");

        demo1_BasicOrderLifecycle();
        demo2_OrderBookMatching();
        demo3_RiskChecks();
        demo4_MarketMaking();
        demo5_DisruptorPipeline();
        demo6_LatencyMeasurement();
    }

    // ------------------------------------------------------------------
    // Demo 1: Basic Order Lifecycle
    // ------------------------------------------------------------------
    static void demo1_BasicOrderLifecycle() {
        System.out.println("--- DEMO 1: Basic Order Lifecycle ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        oms.updateMarketPrice("AAPL", 150.00);

        // Limit buy order — rests in book (no matching sell yet)
        Order buy = new Order("CLI-001", "AAPL", Side.BUY, OrderType.LIMIT, 149.50, 100);
        oms.submitOrder(buy);
        System.out.println("After BUY limit:   " + buy);

        // Matching sell limit order
        Order sell = new Order("CLI-002", "AAPL", Side.SELL, OrderType.LIMIT, 149.50, 60);
        oms.submitOrder(sell);
        System.out.println("After SELL 60:     " + buy);
        System.out.println("Sell order status: " + sell);

        // Market order sweeps remaining quantity
        Order mktSell = new Order("CLI-003", "AAPL", Side.SELL, OrderType.MARKET, 0, 40);
        oms.submitOrder(mktSell);
        System.out.println("After MARKET SELL: " + buy);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 2: Order Book Depth and Matching
    // ------------------------------------------------------------------
    static void demo2_OrderBookMatching() {
        System.out.println("--- DEMO 2: Order Book Matching ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        oms.updateMarketPrice("MSFT", 300.00);

        // Build up the book with multiple price levels
        oms.submitOrder(new Order("B1", "MSFT", Side.BUY,  OrderType.LIMIT, 299.00, 200));
        oms.submitOrder(new Order("B2", "MSFT", Side.BUY,  OrderType.LIMIT, 298.50, 300));
        oms.submitOrder(new Order("B3", "MSFT", Side.BUY,  OrderType.LIMIT, 298.00, 500));
        oms.submitOrder(new Order("A1", "MSFT", Side.SELL, OrderType.LIMIT, 301.00, 200));
        oms.submitOrder(new Order("A2", "MSFT", Side.SELL, OrderType.LIMIT, 301.50, 300));
        oms.submitOrder(new Order("A3", "MSFT", Side.SELL, OrderType.LIMIT, 302.00, 500));

        OrderBook book = oms.getBook("MSFT");
        book.printBook(5);

        System.out.printf("Best Bid: %.2f   Best Ask: %.2f   Spread: %.2f   Mid: %.2f%n",
                book.getBestBid(), book.getBestAsk(), book.getSpread(), book.getMidPrice());

        // Aggressive buy that sweeps two ask levels
        System.out.println("\nAggressive BUY 400 @ 302.00 — should sweep two ask levels:");
        Order aggressive = new Order("AGG-BUY", "MSFT", Side.BUY, OrderType.LIMIT, 302.00, 400);
        List<Trade> trades = new java.util.ArrayList<>();
        oms.setExecutionListener((o, t) -> trades.addAll(t));
        oms.submitOrder(aggressive);
        trades.forEach(t -> System.out.println("  " + t));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 3: Pre-Trade Risk Checks
    // ------------------------------------------------------------------
    static void demo3_RiskChecks() {
        System.out.println("--- DEMO 3: Pre-Trade Risk Checks ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        oms.updateMarketPrice("TSLA", 200.00);

        // Fat finger: price 50% away from market
        Order fatFinger = new Order("FF-001", "TSLA", Side.BUY, OrderType.LIMIT, 300.00, 100);
        oms.submitOrder(fatFinger);
        System.out.println("Fat finger check: " + fatFinger.getStatus());

        // Notional limit: 5000 shares at $200 = $1M — should pass
        Order largeOk = new Order("LG-001", "TSLA", Side.BUY, OrderType.LIMIT, 200.00, 4999);
        oms.submitOrder(largeOk);
        System.out.println("Notional $999,800: " + largeOk.getStatus());

        // Notional limit breach: $200 * 5001 = $1,000,200 > $1M
        Order tooLarge = new Order("LG-002", "TSLA", Side.BUY, OrderType.LIMIT, 200.00, 5001);
        oms.submitOrder(tooLarge);
        System.out.println("Notional $1,000,200 (over limit): " + tooLarge.getStatus());

        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 4: Market Making Strategy
    // ------------------------------------------------------------------
    static void demo4_MarketMaking() {
        System.out.println("--- DEMO 4: Market Making ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        MarketMaker mm = new MarketMaker(oms, "GOOGL", 0.25, 100);

        // Market maker quotes around a series of mid-price updates
        double[] midPrices = { 140.00, 140.05, 139.95, 140.10 };
        for (double mid : midPrices) {
            mm.requote(mid);
        }

        OrderBook book = oms.getBook("GOOGL");
        if (book != null) {
            book.printBook(3);
            System.out.printf("Market Maker spread: %.2f%n", book.getSpread());
        }

        // Simulate a client hitting the market maker's ask
        System.out.println("Client hits the ask — BUY 50 @ market:");
        Order clientBuy = new Order("CLIENT-001", "GOOGL", Side.BUY, OrderType.MARKET, 0, 50);
        oms.submitOrder(clientBuy);
        System.out.println("Client order: " + clientBuy);
        System.out.println("MM position:  " + oms.getPosition("GOOGL") + " (negative = short)");

        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 5: Disruptor Pipeline
    // ------------------------------------------------------------------
    static void demo5_DisruptorPipeline() throws InterruptedException {
        System.out.println("--- DEMO 5: LMAX Disruptor Order Pipeline ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        oms.updateMarketPrice("AMZN", 180.00);

        DisruptorOrderPipeline pipeline = new DisruptorOrderPipeline(oms);

        // Publish 5 orders through the ring buffer
        for (int i = 1; i <= 5; i++) {
            Order order = new Order("DISR-" + i, "AMZN",
                    i % 2 == 0 ? Side.SELL : Side.BUY,
                    OrderType.LIMIT, 179.00 + i * 0.50, 100);
            pipeline.publish(order);
        }

        // Give handlers time to process
        Thread.sleep(200);
        pipeline.shutdown();

        System.out.println("Orders processed via Disruptor ring buffer.");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Demo 6: Latency Measurement
    // ------------------------------------------------------------------
    static void demo6_LatencyMeasurement() {
        System.out.println("--- DEMO 6: Order Processing Latency ---");

        OrderManagementSystem oms = new OrderManagementSystem();
        oms.updateMarketPrice("SPY", 500.00);

        LatencyTimer timer = new LatencyTimer(1000);
        int warmup = 100;
        int samples = 1000;

        // Warmup (JIT compilation)
        for (int i = 0; i < warmup; i++) {
            Order o = new Order("W-" + i, "SPY", Side.BUY, OrderType.LIMIT, 499.50, 10);
            oms.submitOrder(o);
        }

        // Measure
        for (int i = 0; i < samples; i++) {
            Order o = new Order("L-" + i, "SPY", Side.BUY, OrderType.LIMIT, 499.50, 10);
            timer.start();
            oms.submitOrder(o);
            long ns = timer.stop();
            if (i < 5) {
                System.out.printf("  Order %d: %d ns%n", i, ns);
            }
        }

        timer.printStats("OMS submitOrder");
        System.out.println();
    }
}
