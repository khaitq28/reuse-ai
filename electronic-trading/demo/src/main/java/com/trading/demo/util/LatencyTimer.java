package com.trading.demo.util;

import java.util.LongSummaryStatistics;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nanosecond-resolution latency tracker.
 *
 * Measures end-to-end order processing latency.
 * In production: use HdrHistogram for accurate percentile reporting (p50/p99/p999).
 */
public class LatencyTimer {

    private final long[] samples;
    private final AtomicLong count = new AtomicLong(0);
    private final int capacity;

    public LatencyTimer(int capacity) {
        this.capacity = capacity;
        this.samples = new long[capacity];
    }

    private long startNs;

    public void start() {
        startNs = System.nanoTime();
    }

    public long stop() {
        long latencyNs = System.nanoTime() - startNs;
        int idx = (int) (count.getAndIncrement() % capacity);
        samples[idx] = latencyNs;
        return latencyNs;
    }

    public void printStats(String label) {
        long n = Math.min(count.get(), capacity);
        if (n == 0) {
            System.out.println(label + ": no samples");
            return;
        }

        long sum = 0, min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < n; i++) {
            sum += samples[i];
            if (samples[i] < min) min = samples[i];
            if (samples[i] > max) max = samples[i];
        }

        System.out.printf("%s latency (%d samples): min=%dns avg=%dns max=%dns%n",
                label, n, min, sum / n, max);
    }
}
