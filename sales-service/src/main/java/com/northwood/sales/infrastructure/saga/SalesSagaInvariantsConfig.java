package com.northwood.sales.infrastructure.saga;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.infrastructure.saga.SagaStateInvariantCheck;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the saga-state invariant for {@code sales_order_fulfilment_saga}.
 * The shared {@code SagaStateInvariantChecker} runs this on
 * {@code ApplicationReadyEvent} and fails the boot if any state in
 * {@link SalesOrderFulfilmentSaga#ALL_STATES} is missing from the schema CHECK.
 */
@Configuration
public class SalesSagaInvariantsConfig {

    @Bean
    public SagaStateInvariantCheck salesOrderFulfilmentSagaStates() {
        return new SagaStateInvariantCheck() {
            @Override public String schemaName() { return "sales"; }
            @Override public String tableName()  { return "sales_order_fulfilment_saga"; }
            @Override public Set<String> codeStates() { return SalesOrderFulfilmentSaga.ALL_STATES; }
        };
    }
}
