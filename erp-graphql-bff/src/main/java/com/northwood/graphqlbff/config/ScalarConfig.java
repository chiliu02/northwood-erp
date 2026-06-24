package com.northwood.graphqlbff.config;

import graphql.scalars.ExtendedScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Registers the graphql-java extended scalars used by the schema:
 * {@code BigDecimal} (monetary amounts / quantities), {@code Date}
 * ({@code java.time.LocalDate}) and {@code DateTime}
 * ({@code java.time.OffsetDateTime}).
 */
@Configuration
public class ScalarConfig {

    @Bean
    public RuntimeWiringConfigurer scalarRuntimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
            .scalar(ExtendedScalars.GraphQLBigDecimal)
            .scalar(ExtendedScalars.Date)
            .scalar(ExtendedScalars.DateTime);
    }
}
