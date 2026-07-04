package com.trading.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger configuration.
 *
 * UI:   http://localhost:8080/swagger-ui.html
 * JSON: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Electronic Trading API")
                        .description("""
                                REST API for the Order Management System (OMS).

                                Flow:
                                1. Seed a market reference price  →  PUT /api/market-prices/{symbol}
                                2. Submit orders                  →  POST /api/orders
                                3. Check fills in the response    →  trades[] array
                                4. Query net position             →  GET /api/positions/{symbol}
                                5. View order book snapshot       →  GET /api/orderbook/{symbol}
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Trading Desk")
                                .email("trading@example.com")))
                .tags(List.of(
                        new Tag().name("Orders").description("Submit, cancel and query orders"),
                        new Tag().name("Positions").description("Net position per symbol"),
                        new Tag().name("Order Book").description("CLOB best bid/ask snapshot"),
                        new Tag().name("Market Data").description("Reference price management")
                ));
    }
}
