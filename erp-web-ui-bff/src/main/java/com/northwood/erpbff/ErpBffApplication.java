package com.northwood.erpbff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend-for-Frontend for the operational ERP SPA (erp-web-ui).
 * Sibling of {@code web-ui-bff}; runs on port 8089 so the two SPAs deploy
 * independently. The OAuth2 / Keycloak wiring lands here only — the
 * demo BFF stays anonymous.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ErpBffTargets.class)
public class ErpBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpBffApplication.class, args);
    }
}
