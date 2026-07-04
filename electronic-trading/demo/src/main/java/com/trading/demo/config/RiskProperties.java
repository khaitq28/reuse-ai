package com.trading.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised risk limits — configure in application.yml under trading.risk.*
 *
 * In a real bank these limits are stored in a risk management database and
 * reloaded live (without restart) via a config-server / feature-flag service.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trading.risk")
public class RiskProperties {

    /** Maximum notional value per single order (USD) */
    private double maxNotionalPerOrder = 1_000_000.0;

    /** Maximum absolute position per symbol (shares) */
    private long maxPositionPerSymbol = 100_000L;

    /** Maximum price deviation from last market price before fat-finger rejection */
    private double maxPriceDeviationPct = 0.10;

    /** Maximum number of orders accepted per second (rate limit) */
    private long maxOrdersPerSecond = 100;
}
