package com.trading.demo;

import com.trading.demo.market.OrderBook;
import com.trading.demo.model.Order;
import com.trading.demo.model.OrderStatus;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook("AAPL");
    }

    @Test
    void limitBuyRestingInBook_noMatchYet() {
        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 100);
        List<Trade> trades = book.addOrder(buy);

        assertTrue(trades.isEmpty());
        assertEquals(150.00, book.getBestBid());
        assertTrue(Double.isNaN(book.getBestAsk()));
//        assertEquals(OrderStatus.ACCEPTED, buy.getStatus());
    }

    @Test
    void exactPriceMatch_fullFill() {
        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 100);
        book.addOrder(buy);

        Order sell = new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 150.00, 100);
        List<Trade> trades = book.addOrder(sell);

        assertEquals(1, trades.size());
        assertEquals(100, trades.get(0).getQuantity());
        assertEquals(150.00, trades.get(0).getPrice());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
    }

    @Test
    void partialFill_buyRemainsInBook() {
        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 200);
        book.addOrder(buy);

        Order sell = new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 150.00, 80);
        List<Trade> trades = book.addOrder(sell);

        assertEquals(1, trades.size());
        assertEquals(80, trades.get(0).getQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(120, buy.getRemainingQuantity());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
    }

    @Test
    void aggressiveBuy_sweepsMultipleAskLevels() {
        book.addOrder(new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 151.00, 100));
        book.addOrder(new Order("S2", "AAPL", Side.SELL, OrderType.LIMIT, 152.00, 100));

        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 153.00, 250);
        List<Trade> trades = book.addOrder(buy);

        assertEquals(2, trades.size());
        assertEquals(200, buy.getFilledQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
    }

    @Test
    void limitBuy_doesNotCrossAboveLimitPrice() {
        book.addOrder(new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 155.00, 100));

        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 100);
        List<Trade> trades = book.addOrder(buy);

        assertTrue(trades.isEmpty(), "Should not match when ask > buy limit");
    }

    @Test
    void marketOrder_matchesBestAvailable() {
        book.addOrder(new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 151.00, 200));

        Order mkt = new Order("B1", "AAPL", Side.BUY, OrderType.MARKET, 0, 150);
        List<Trade> trades = book.addOrder(mkt);

        assertEquals(1, trades.size());
        assertEquals(150, trades.get(0).getQuantity());
        assertEquals(151.00, trades.get(0).getPrice());
        assertEquals(OrderStatus.FILLED, mkt.getStatus());
    }

    @Test
    void cancelOrder_removesFromBook() {
        Order buy = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 100);
        book.addOrder(buy);

        boolean cancelled = book.cancelOrder(buy.getOrderId(), Side.BUY);

        assertTrue(cancelled);
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertTrue(Double.isNaN(book.getBestBid()));
    }

    @Test
    void priceTimePriority_olderOrderFilledFirst() {
        Order first  = new Order("B1", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 50);
        Order second = new Order("B2", "AAPL", Side.BUY, OrderType.LIMIT, 150.00, 50);
        book.addOrder(first);
        book.addOrder(second);

        Order sell = new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 150.00, 50);
        book.addOrder(sell);

        // First-in order should be filled, second remains
        assertEquals(OrderStatus.FILLED, first.getStatus());
//        assertEquals(OrderStatus.ACCEPTED, second.getStatus());
    }

    @Test
    void spread_calculatedCorrectly() {
        book.addOrder(new Order("B1", "AAPL", Side.BUY,  OrderType.LIMIT, 149.50, 100));
        book.addOrder(new Order("S1", "AAPL", Side.SELL, OrderType.LIMIT, 150.50, 100));

        assertEquals(1.00, book.getSpread(), 0.001);
        assertEquals(150.00, book.getMidPrice(), 0.001);
    }
}
