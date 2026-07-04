package com.trading.demo.api.dto;

import com.trading.demo.model.OrderType;
import com.trading.demo.model.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound REST payload for submitting a new order.
 */
@Getter
@Setter
@Schema(description = "New order request")
public class OrderRequest {

    @Schema(description = "Client-assigned order reference", example = "CLI-001")
    private String clientOrderId;

    @Schema(description = "Instrument symbol", example = "AAPL")
    private String symbol;

    @Schema(description = "Order direction", example = "BUY")
    private Side side;

    @Schema(description = "LIMIT executes at price or better; MARKET executes at best available", example = "LIMIT")
    private OrderType orderType;

    @Schema(description = "Limit price in USD; set 0 for MARKET orders", example = "149.50")
    private double price;

    @Schema(description = "Number of shares", example = "100")
    private long quantity;
}
