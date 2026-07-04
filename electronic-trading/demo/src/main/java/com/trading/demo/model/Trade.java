package com.trading.demo.model;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents an execution (fill) — a match between a buy and sell order.
 * In FIX terms: ExecutionReport (35=8) with ExecType=F (Trade).
 */
@Getter
@ToString
public class Trade {

    private static final AtomicLong TRADE_ID_GEN = new AtomicLong(1);

    private final long    tradeId;
    private final long    buyOrderId;
    private final long    sellOrderId;
    private final String  symbol;
    private final long    quantity;
    private final double  price;
    private final Instant executedAt;

    public Trade(long buyOrderId, long sellOrderId, String symbol, long quantity, double price) {
        this.tradeId     = TRADE_ID_GEN.getAndIncrement();
        this.buyOrderId  = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.symbol      = symbol;
        this.quantity    = quantity;
        this.price       = price;
        this.executedAt  = Instant.now();
    }

    public double getNotional() { return quantity * price; }
}
