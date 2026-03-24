package com.comparison.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot application simulating a microservices checkout flow.
 *
 * <p>Each endpoint represents a separate microservice. The orchestrator
 * ({@code POST /checkout}) calls each one via HTTP — the standard
 * microservices pattern. This is the SAME business logic as KubeFn's
 * checkout pipeline, but with 7 HTTP hops and 14 JSON serialization
 * round-trips instead of zero-copy heap sharing.
 */
@SpringBootApplication
public class CheckoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
