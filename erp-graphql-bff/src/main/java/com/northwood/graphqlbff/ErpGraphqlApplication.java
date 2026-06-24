package com.northwood.graphqlbff;

import com.northwood.graphqlbff.upstream.ErpTargets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * GraphQL Backend-for-Frontend for Northwood (port 8088), parallel to
 * {@code erp-web-ui-bff}. Exposes one typed GraphQL endpoint that stitches the
 * REST services together with DataLoader batching, a {@code placeOrder}
 * mutation, and a live {@code *.events} Kafka subscription (kafka profile).
 */
@SpringBootApplication
@EnableConfigurationProperties(ErpTargets.class)
public class ErpGraphqlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpGraphqlApplication.class, args);
    }
}
