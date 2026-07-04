package com.trading.demo.api;

import com.trading.demo.api.dto.OrderRequest;
import com.trading.demo.api.dto.OrderResponse;
import com.trading.demo.market.OrderBook;
import com.trading.demo.model.Order;
import com.trading.demo.model.Trade;
import com.trading.demo.service.OrderManagementSystem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API for the Order Management System.
 *
 * In a real bank this layer would also:
 *  - validate the authenticated trader's book permissions
 *  - publish execution events to Kafka / Solace
 *  - persist orders to a trade-store (Sybase IQ / PostgreSQL)
 *  - enforce MiFID II audit-trail requirements
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderManagementSystem oms;

    public OrderController(OrderManagementSystem oms) {
        this.oms = oms;
    }

    // ------------------------------------------------------------------
    // Submit order  —  POST /api/orders
    // ------------------------------------------------------------------

    @Tag(name = "Orders")
    @Operation(
        summary = "Submit a new order",
        description = """
                Runs pre-trade risk checks (notional, fat-finger, position limit, rate limit)
                then routes to the CLOB matching engine. Any fills are returned in the
                `trades` array of the response.

                **Typical flow:**
                1. Call `PUT /api/market-prices/{symbol}` to seed a reference price.
                2. Submit a LIMIT BUY — it rests in the order book.
                3. Submit a matching LIMIT SELL — the response contains the fill.
                """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = {
                @ExampleObject(name = "Limit Buy", value = """
                        {
                          "clientOrderId": "CLI-001",
                          "symbol": "AAPL",
                          "side": "BUY",
                          "orderType": "LIMIT",
                          "price": 149.50,
                          "quantity": 100
                        }"""),
                @ExampleObject(name = "Market Sell", value = """
                        {
                          "clientOrderId": "CLI-002",
                          "symbol": "AAPL",
                          "side": "SELL",
                          "orderType": "MARKET",
                          "price": 0,
                          "quantity": 50
                        }""")
            })
        ),
        responses = {
            @ApiResponse(responseCode = "200", description = "Order accepted (check status field — may be REJECTED by risk engine)")
        }
    )
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> submitOrder(@RequestBody OrderRequest req) {

        Order order = new Order(
                req.getClientOrderId(),
                req.getSymbol(),
                req.getSide(),
                req.getOrderType(),
                req.getPrice(),
                req.getQuantity()
        );

        List<Trade> fills = new ArrayList<>();
        oms.setExecutionListener((o, trades) -> fills.addAll(trades));

        Order result = oms.submitOrder(order);
        return ResponseEntity.ok(OrderResponse.from(result, fills));
    }

    // ------------------------------------------------------------------
    // Cancel order  —  DELETE /api/orders/{id}
    // ------------------------------------------------------------------

    @Tag(name = "Orders")
    @Operation(
        summary = "Cancel an order",
        description = "Removes a resting order from the order book. Returns 404 if the order does not exist or is already filled/cancelled.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Order cancelled"),
            @ApiResponse(responseCode = "404", description = "Order not found or not cancellable", content = @Content)
        }
    )
    @DeleteMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @Parameter(description = "Internal order ID returned by POST /api/orders") @PathVariable long id) {

        boolean cancelled = oms.cancelOrder(id);
        if (!cancelled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("orderId", id, "status", "CANCELLED"));
    }

    // ------------------------------------------------------------------
    // Get order  —  GET /api/orders/{id}
    // ------------------------------------------------------------------

    @Tag(name = "Orders")
    @Operation(
        summary = "Get order status",
        responses = {
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
        }
    )
    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "Internal order ID") @PathVariable long id) {

        Order order = oms.getOrder(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    // ------------------------------------------------------------------
    // Net position  —  GET /api/positions/{symbol}
    // ------------------------------------------------------------------

    @Tag(name = "Positions")
    @Operation(
        summary = "Get net position for a symbol",
        description = "Returns the net position in shares. Positive = LONG, negative = SHORT, zero = FLAT.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Position snapshot",
                content = @Content(schema = @Schema(example = """
                        {"symbol":"AAPL","position":500,"side":"LONG"}""")))
        }
    )
    @GetMapping("/positions/{symbol}")
    public ResponseEntity<Map<String, Object>> getPosition(
            @Parameter(description = "Instrument symbol, e.g. AAPL") @PathVariable String symbol) {

        long position = oms.getPosition(symbol);
        return ResponseEntity.ok(Map.of(
                "symbol",   symbol,
                "position", position,
                "side",     position > 0 ? "LONG" : position < 0 ? "SHORT" : "FLAT"
        ));
    }

    // ------------------------------------------------------------------
    // Order book snapshot  —  GET /api/orderbook/{symbol}
    // ------------------------------------------------------------------

    @Tag(name = "Order Book")
    @Operation(
        summary = "Order book best bid/ask snapshot",
        description = "Returns best bid, best ask, spread, and mid-price. Values are NaN when no orders exist on that side.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Snapshot returned",
                content = @Content(schema = @Schema(example = """
                        {"symbol":"AAPL","bestBid":149.50,"bestAsk":150.50,"spread":1.00,"midPrice":150.00}""")))
        }
    )
    @GetMapping("/orderbook/{symbol}")
    public ResponseEntity<Map<String, Object>> getOrderBook(
            @Parameter(description = "Instrument symbol") @PathVariable String symbol) {

        OrderBook book = oms.getBook(symbol);
        if (book == null) {
            return ResponseEntity.ok(Map.of("symbol", symbol, "message", "No orders yet"));
        }
        return ResponseEntity.ok(Map.of(
                "symbol",   symbol,
                "bestBid",  book.getBestBid(),
                "bestAsk",  book.getBestAsk(),
                "spread",   book.getSpread(),
                "midPrice", book.getMidPrice()
        ));
    }

    // ------------------------------------------------------------------
    // Update market reference price  —  PUT /api/market-prices/{symbol}
    // ------------------------------------------------------------------

    @Tag(name = "Market Data")
    @Operation(
        summary = "Set reference market price",
        description = """
                Seeds the risk engine with a reference price used for fat-finger detection.
                In production this is pushed automatically by a market data feed (Bloomberg B-PIPE, Refinitiv TREP).
                """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {"price": 150.00}"""))
        ),
        responses = {
            @ApiResponse(responseCode = "200", description = "Price updated"),
            @ApiResponse(responseCode = "400", description = "Price missing or non-positive", content = @Content)
        }
    )
    @PutMapping("/market-prices/{symbol}")
    public ResponseEntity<Map<String, Object>> updateMarketPrice(
            @Parameter(description = "Instrument symbol") @PathVariable String symbol,
            @RequestBody Map<String, Double> body) {

        Double price = body.get("price");
        if (price == null || price <= 0) {
            return ResponseEntity.badRequest().build();
        }
        oms.updateMarketPrice(symbol, price);
        return ResponseEntity.ok(Map.of("symbol", symbol, "price", price));
    }
}
