package com.trading.demo.model;

/**
 * Order lifecycle states (mirrors FIX ExecutionReport OrdStatus field 39).
 */
public enum OrderStatus {
    NEW,              // Order accepted by OMS
    PENDING_NEW,      // Sent to exchange, awaiting ack
    ACCEPTED,         // Acknowledged by exchange
    PARTIALLY_FILLED, // Some quantity filled
    FILLED,           // Entire quantity filled
    CANCELLED,        // Cancelled by client or exchange
    REJECTED,         // Rejected by risk or exchange
    EXPIRED           // GTC/GTD order expired
}
