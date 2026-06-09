package com.trading.demo.market;

import com.trading.demo.model.Order;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;

import java.util.*;

/**
 * Central Limit Order Book (CLOB) for a single instrument.
 *
 * Maintains:
 *   - Bids: sorted descending by price (highest bid first)
 *   - Asks: sorted ascending by price  (lowest ask first)
 *
 * Matching rule: price-time priority (FIFO at same price level).
 *
 * Production considerations:
 *   - Use array-backed price levels instead of TreeMap for cache efficiency
 *   - Pre-allocate level objects to avoid GC
 *   - Separate read/write paths with lock-free structures
 */
public class OrderBook {

    private final String symbol;

    // Bids: highest price first
    private final TreeMap<Double, Deque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

    // Asks: lowest price first
    private final TreeMap<Double, Deque<Order>> asks =
            new TreeMap<>();

    private final List<Trade> trades = new ArrayList<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Add an order and attempt to match it immediately.
     * Returns list of trades generated (may be empty).
     */
    public List<Trade> addOrder(Order incoming) {
        List<Trade> newTrades = new ArrayList<>();

        if (incoming.getSide() == Side.BUY) {
            matchAgainstAsks(incoming, newTrades);
            if (incoming.isActive() && incoming.getOrderType() != com.trading.demo.model.OrderType.MARKET) {
                addToBook(bids, incoming);
            }
        } else {
            matchAgainstBids(incoming, newTrades);
            if (incoming.isActive() && incoming.getOrderType() != com.trading.demo.model.OrderType.MARKET) {
                addToBook(asks, incoming);
            }
        }

        trades.addAll(newTrades);
        return newTrades;
    }

    private void matchAgainstAsks(Order buyOrder, List<Trade> result) {
        while (buyOrder.isActive() && !asks.isEmpty()) {
            Map.Entry<Double, Deque<Order>> bestAsk = asks.firstEntry();
            double askPrice = bestAsk.getKey();

            // Limit order: only match if ask <= buy limit price
            if (buyOrder.getOrderType() == com.trading.demo.model.OrderType.LIMIT
                    && askPrice > buyOrder.getPrice()) {
                break;
            }

            matchAtLevel(buyOrder, bestAsk.getValue(), askPrice, result);

            if (bestAsk.getValue().isEmpty()) {
                asks.pollFirstEntry();
            }
        }
    }

    private void matchAgainstBids(Order sellOrder, List<Trade> result) {
        while (sellOrder.isActive() && !bids.isEmpty()) {
            Map.Entry<Double, Deque<Order>> bestBid = bids.firstEntry();
            double bidPrice = bestBid.getKey();

            // Limit order: only match if bid >= sell limit price
            if (sellOrder.getOrderType() == com.trading.demo.model.OrderType.LIMIT
                    && bidPrice < sellOrder.getPrice()) {
                break;
            }

            matchAtLevel(sellOrder, bestBid.getValue(), bidPrice, result);

            if (bestBid.getValue().isEmpty()) {
                bids.pollFirstEntry();
            }
        }
    }

    private void matchAtLevel(Order aggressive, Deque<Order> passiveQueue,
                               double matchPrice, List<Trade> result) {
        while (aggressive.isActive() && !passiveQueue.isEmpty()) {
            Order passive = passiveQueue.peek();
            long matchQty = Math.min(aggressive.getRemainingQuantity(), passive.getRemainingQuantity());

            Trade trade = (aggressive.getSide() == Side.BUY)
                    ? new Trade(aggressive.getOrderId(), passive.getOrderId(), symbol, matchQty, matchPrice)
                    : new Trade(passive.getOrderId(), aggressive.getOrderId(), symbol, matchQty, matchPrice);

            aggressive.applyFill(matchQty, matchPrice);
            passive.applyFill(matchQty, matchPrice);
            result.add(trade);

            if (!passive.isActive()) {
                passiveQueue.poll();
            }
        }
    }

    private void addToBook(TreeMap<Double, Deque<Order>> side, Order order) {
        side.computeIfAbsent(order.getPrice(), k -> new ArrayDeque<>()).offer(order);
    }

    public boolean cancelOrder(long orderId, Side side) {
        TreeMap<Double, Deque<Order>> book = (side == Side.BUY) ? bids : asks;
        for (Map.Entry<Double, Deque<Order>> entry : book.entrySet()) {
            Deque<Order> level = entry.getValue();
            for (Order o : level) {
                if (o.getOrderId() == orderId) {
                    o.cancel();
                    level.remove(o);
                    if (level.isEmpty()) book.remove(entry.getKey());
                    return true;
                }
            }
        }
        return false;
    }

    // --- Market data queries ---

    public double getBestBid() {
        return bids.isEmpty() ? Double.NaN : bids.firstKey();
    }

    public double getBestAsk() {
        return asks.isEmpty() ? Double.NaN : asks.firstKey();
    }

    public double getMidPrice() {
        if (bids.isEmpty() || asks.isEmpty()) return Double.NaN;
        return (getBestBid() + getBestAsk()) / 2.0;
    }

    public double getSpread() {
        return getBestAsk() - getBestBid();
    }

    public long getBidDepthAtPrice(double price) {
        Deque<Order> level = bids.get(price);
        if (level == null) return 0;
        return level.stream().mapToLong(Order::getRemainingQuantity).sum();
    }

    public long getAskDepthAtPrice(double price) {
        Deque<Order> level = asks.get(price);
        if (level == null) return 0;
        return level.stream().mapToLong(Order::getRemainingQuantity).sum();
    }

    public String getSymbol() { return symbol; }
    public List<Trade> getTrades() { return Collections.unmodifiableList(trades); }

    public void printBook(int depth) {
        System.out.println("\n=== Order Book: " + symbol + " ===");
        System.out.println("ASKS:");
        asks.entrySet().stream()
                .limit(depth)
                .forEach(e -> {
                    long qty = e.getValue().stream().mapToLong(Order::getRemainingQuantity).sum();
                    System.out.printf("  %.2f  %d%n", e.getKey(), qty);
                });
        System.out.printf("  --- Spread: %.2f ---%n", getSpread());
        System.out.println("BIDS:");
        bids.entrySet().stream()
                .limit(depth)
                .forEach(e -> {
                    long qty = e.getValue().stream().mapToLong(Order::getRemainingQuantity).sum();
                    System.out.printf("  %.2f  %d%n", e.getKey(), qty);
                });
        System.out.println();
    }
}
