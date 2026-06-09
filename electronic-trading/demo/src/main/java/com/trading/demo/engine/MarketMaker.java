package com.trading.demo.engine;

import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.service.OrderManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Market Making strategy.
 *
 * A market maker continuously quotes:
 *   - BID = midPrice - halfSpread
 *   - ASK = midPrice + halfSpread
 *
 * When filled, it re-quotes. Profit comes from capturing the spread.
 * Risk: inventory builds up if market moves in one direction (adverse selection).
 *
 * This is a simplified demo — real market makers also:
 *   - Skew quotes based on inventory (lean away from large positions)
 *   - Use volatility-adjusted spreads
 *   - Cancel/replace quotes on every market data update (microsecond latency)
 */
public class MarketMaker {

    private static final Logger log = LoggerFactory.getLogger(MarketMaker.class);

    private final OrderManagementSystem oms;
    private final String symbol;
    private final double halfSpread;   // Half of bid-ask spread
    private final long quoteSize;      // Size of each quote

    private final List<Long> activeBidIds = new ArrayList<>();
    private final List<Long> activeAskIds = new ArrayList<>();
    private final AtomicInteger quoteCounter = new AtomicInteger(0);

    public MarketMaker(OrderManagementSystem oms, String symbol,
                       double halfSpread, long quoteSize) {
        this.oms = oms;
        this.symbol = symbol;
        this.halfSpread = halfSpread;
        this.quoteSize = quoteSize;
    }

    /**
     * Update quotes around a new mid-price.
     * Cancels existing quotes and submits new ones.
     */
    public void requote(double midPrice) {
        cancelActiveQuotes();

        double bidPrice = round2dp(midPrice - halfSpread);
        double askPrice = round2dp(midPrice + halfSpread);

        int seq = quoteCounter.incrementAndGet();

        Order bid = new Order("MM-BID-" + seq, symbol, Side.BUY,
                OrderType.LIMIT, bidPrice, quoteSize);
        Order ask = new Order("MM-ASK-" + seq, symbol, Side.SELL,
                OrderType.LIMIT, askPrice, quoteSize);

        oms.updateMarketPrice(symbol, midPrice);

        Order submittedBid = oms.submitOrder(bid);
        Order submittedAsk = oms.submitOrder(ask);

        if (submittedBid.isActive()) activeBidIds.add(submittedBid.getOrderId());
        if (submittedAsk.isActive()) activeAskIds.add(submittedAsk.getOrderId());

        log.info("MarketMaker quoted: BID={} ASK={} mid={}", bidPrice, askPrice, midPrice);
    }

    private void cancelActiveQuotes() {
        activeBidIds.forEach(oms::cancelOrder);
        activeAskIds.forEach(oms::cancelOrder);
        activeBidIds.clear();
        activeAskIds.clear();
    }

    private double round2dp(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
