package com.trading.demo.api.dto;

import com.trading.demo.model.Order;
import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Outbound REST response for order operations.
 * Maps the internal {@link Order} domain model to a JSON-friendly DTO.
 */
@Getter
@Builder
public class OrderResponse {

    private final long         orderId;
    private final String       clientOrderId;
    private final String       symbol;
    private final Side         side;
    private final OrderType    orderType;
    private final double       price;
    private final long         quantity;
    private final String       status;
    private final long         filledQuantity;
    private final double       avgFillPrice;
    private final String       createdAt;
    private final List<TradeDto> trades;

    public static OrderResponse from(Order order, List<Trade> trades) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .price(order.getPrice())
                .quantity(order.getQuantity())
                .status(order.getStatus().name())
                .filledQuantity(order.getFilledQuantity())
                .avgFillPrice(order.getAvgFillPrice())
                .createdAt(order.getCreatedAt().toString())
                .trades(trades.stream().map(TradeDto::from).toList())
                .build();
    }

    public static OrderResponse from(Order order) {
        return from(order, List.of());
    }

    // ------------------------------------------------------------------
    // Nested DTO for trade fills included in the response
    // ------------------------------------------------------------------

    @Getter
    @Builder
    public static class TradeDto {
        private final long   tradeId;
        private final long   buyOrderId;
        private final long   sellOrderId;
        private final String symbol;
        private final long   quantity;
        private final double price;
        private final double notional;
        private final String executedAt;

        public static TradeDto from(Trade t) {
            return TradeDto.builder()
                    .tradeId(t.getTradeId())
                    .buyOrderId(t.getBuyOrderId())
                    .sellOrderId(t.getSellOrderId())
                    .symbol(t.getSymbol())
                    .quantity(t.getQuantity())
                    .price(t.getPrice())
                    .notional(t.getNotional())
                    .executedAt(t.getExecutedAt().toString())
                    .build();
        }
    }
}
