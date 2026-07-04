package com.trading.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.trading.demo.config.RiskProperties;

/**
 * Spring Boot entry point for the electronic trading system.
 *
 * Exposes REST endpoints for order management, position queries, and order book snapshots.
 * The matching engine (OrderBook), OMS and risk controls run entirely in-memory.
 *
 * Run:  mvn spring-boot:run
 * Health: http://localhost:8080/actuator/health
 * API:    http://localhost:8080/api/orders
 */
@SpringBootApplication
@EnableConfigurationProperties(RiskProperties.class)
public class TradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
