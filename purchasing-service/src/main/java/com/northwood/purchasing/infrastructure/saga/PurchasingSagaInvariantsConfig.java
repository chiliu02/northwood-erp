package com.northwood.purchasing.infrastructure.saga;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.shared.infrastructure.saga.SagaStateInvariantCheck;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the saga-state invariant for {@code purchase_to_pay_saga}.
 * Boot fails if any state in {@link PurchaseToPaySaga#ALL_STATES} is missing
 * from the schema CHECK on {@code purchasing.purchase_to_pay_saga.saga_state}.
 */
@Configuration
public class PurchasingSagaInvariantsConfig {

    @Bean
    public SagaStateInvariantCheck purchaseToPaySagaStates() {
        return new SagaStateInvariantCheck() {
            @Override public String schemaName() { return "purchasing"; }
            @Override public String tableName()  { return "purchase_to_pay_saga"; }
            @Override public Set<String> codeStates() { return PurchaseToPaySaga.ALL_STATES; }
        };
    }
}
