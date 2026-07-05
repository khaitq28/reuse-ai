package com.trading.real.service;

import com.trading.demo.model.Side;
import com.trading.demo.model.Trade;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-time Position and P&L Service.
 *
 * Tracks for each symbol:
 *   - Net position (positive = long, negative = short)
 *   - Average cost price (for P&L calculation)
 *   - Realized P&L (from closed/reduced positions)
 *   - Unrealized P&L (mark-to-market: current position × (market price - avg cost))
 *
 * WHY this matters in a front office system:
 *   - Risk limits are enforced against positions (see RiskEngine)
 *   - Traders need real-time P&L to manage intraday exposure
 *   - Regulatory reporting (MiFID II) requires accurate position snapshots
 *   - P&L breach triggers the kill switch
 *
 * Thread-safety:
 *   - ConcurrentHashMap per symbol — concurrent reads from risk checks
 *   - PositionEntry uses synchronized for compound (read-check-update) operations
 *   - In production with a single-threaded OMS event loop, synchronization is not needed
 *     (the OMS thread is the sole writer — zero lock overhead)
 *
 * P&L method: Average Cost (AVCO)
 *   - Simpler than FIFO/LIFO, standard for intraday equity books
 *   - avg_cost updates on every buy; realized P&L crystallizes on every sell
 */
@Service
public class PositionService {

    // --- Per-symbol position state ---
    private final ConcurrentHashMap<String, PositionEntry> positions = new ConcurrentHashMap<>();

    // --- Per-symbol last market price (for unrealized P&L mark-to-market) ---
    private final ConcurrentHashMap<String, Double> marketPrices = new ConcurrentHashMap<>();

    // --- Total realized P&L across all symbols (for kill switch threshold) ---
    // AtomicLong stores P&L × 100 (cents) to avoid floating-point race conditions
    private final AtomicLong totalRealizedPnlCents = new AtomicLong(0);

    // -------------------------------------------------------------------------

    /**
     * Called by OMS after EVERY fill. Updates position and P&L atomically.
     *
     * @param symbol    instrument
     * @param side      BUY or SELL
     * @param fillQty   number of shares filled
     * @param fillPrice price at which the fill occurred
     */
    public void onFill(String symbol, Side side, long fillQty, double fillPrice) {
        PositionEntry entry = positions.computeIfAbsent(symbol, k -> new PositionEntry());
        double realizedPnl = entry.applyFill(side, fillQty, fillPrice);

        // Accumulate realized P&L in atomic cents to avoid floating-point race
        if (realizedPnl != 0) {
            totalRealizedPnlCents.addAndGet(Math.round(realizedPnl * 100));
        }
    }

    /**
     * Update the market price for a symbol — used for mark-to-market unrealized P&L.
     * Called by the market data feed handler on every tick.
     */
    public void updateMarketPrice(String symbol, double price) {
        marketPrices.put(symbol, price);
    }

    // --- Queries ---

    public long   getPosition(String symbol)   { return positions.getOrDefault(symbol, PositionEntry.ZERO).netPosition; }
    public double getAvgCost(String symbol)    { return positions.getOrDefault(symbol, PositionEntry.ZERO).avgCostPrice; }
    public double getTotalRealizedPnl()        { return totalRealizedPnlCents.get() / 100.0; }

    /** Mark-to-market unrealized P&L for a symbol: position × (market_price - avg_cost) */
    public double getUnrealizedPnl(String symbol) {
        PositionEntry entry    = positions.get(symbol);
        Double        mktPrice = marketPrices.get(symbol);
        if (entry == null || mktPrice == null || entry.netPosition == 0) return 0;
        return entry.netPosition * (mktPrice - entry.avgCostPrice);
    }

    /** Total portfolio P&L = realized + sum of all unrealized */
    public double getTotalPnl() {
        double unrealized = positions.keySet().stream()
                .mapToDouble(this::getUnrealizedPnl)
                .sum();
        return getTotalRealizedPnl() + unrealized;
    }

    public Map<String, PositionEntry> getAllPositions() { return positions; }

    // -------------------------------------------------------------------------
    // Inner class: per-symbol position state
    // -------------------------------------------------------------------------

    public static class PositionEntry {
        static final PositionEntry ZERO = new PositionEntry();

        long   netPosition  = 0;      // positive = long, negative = short
        double avgCostPrice = 0;      // weighted average cost basis
        double realizedPnl  = 0;      // cumulative realized P&L for this symbol

        /**
         * Apply a fill and return the realized P&L generated (0 if no position closed).
         *
         * Average cost method (AVCO):
         *   - BUY: new_avg = (old_pos × old_avg + fill_qty × fill_price) / new_pos
         *   - SELL (reducing long): realized = fill_qty × (fill_price - avg_cost)
         */
        public synchronized double applyFill(Side side, long fillQty, double fillPrice) {
            double realized = 0;

            if (side == Side.BUY) {
                // Buying more: update average cost basis
                double totalCost = netPosition * avgCostPrice + fillQty * fillPrice;
                netPosition  += fillQty;
                avgCostPrice  = netPosition == 0 ? 0 : totalCost / netPosition;

            } else { // SELL — reducing long position (or building short)
                if (netPosition > 0) {
                    // Closing/reducing a long: crystallize P&L
                    long closedQty = Math.min(fillQty, netPosition);
                    realized     = closedQty * (fillPrice - avgCostPrice);
                    realizedPnl += realized;

                    long remaining = fillQty - closedQty;
                    netPosition -= closedQty;
                    if (netPosition == 0) avgCostPrice = 0;

                    // If selling more than current long → start a short position
                    if (remaining > 0) {
                        netPosition  = -remaining;
                        avgCostPrice = fillPrice;   // short entered at fill price
                    }
                } else {
                    // Already short or flat: just extend the short
                    double totalCost = Math.abs(netPosition) * avgCostPrice + fillQty * fillPrice;
                    netPosition -= fillQty;
                    avgCostPrice = totalCost / Math.abs(netPosition);
                }
            }
            return realized;
        }

        @Override
        public String toString() {
            return String.format("pos=%d avgCost=%.4f realizedPnl=%.2f",
                    netPosition, avgCostPrice, realizedPnl);
        }
    }
}
