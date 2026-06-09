package com.trading.demo.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents an execution (fill) — a match between a buy and sell order.
 * In FIX terms: ExecutionReport (35=8) with ExecType=F (Trade).
 */
public class Trade {

    private static final AtomicLong TRADE_ID_GEN = new AtomicLong(1);

    private final long tradeId;
    private final long buyOrderId;
    private final long sellOrderId;
    private final String symbol;
    private final long quantity;
    private final double price;
    private final Instant executedAt;

    public Trade(long buyOrderId, long sellOrderId, String symbol, long quantity, double price) {
        this.tradeId = TRADE_ID_GEN.getAndIncrement();
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = Instant.now();
    }

    public long getTradeId() { return tradeId; }
    public long getBuyOrderId() { return buyOrderId; }
    public long getSellOrderId() { return sellOrderId; }
    public String getSymbol() { return symbol; }
    public long getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public double getNotional() { return quantity * price; }
    public Instant getExecutedAt() { return executedAt; }

    @Override
    public String toString() {
        return String.format("Trade{id=%d, %s qty=%d @ %.2f, buy=%d, sell=%d}",
                tradeId, symbol, quantity, price, buyOrderId, sellOrderId);
    }
}
