package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import com.northwood.shared.infrastructure.saga.SagaStateInvariantCheck;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the saga-state invariant for {@code work_order_saga}.
 * Boot fails if any state in {@link WorkOrderSaga#ALL_STATES} is missing
 * from the schema CHECK on {@code manufacturing.work_order_saga.saga_state}.
 */
@Configuration
public class ManufacturingSagaInvariantsConfig {

    @Bean
    public SagaStateInvariantCheck workOrderSagaStates() {
        return new SagaStateInvariantCheck() {
            @Override public String schemaName() { return "manufacturing"; }
            @Override public String tableName()  { return "work_order_saga"; }
            @Override public Set<String> codeStates() { return WorkOrderSaga.ALL_STATES; }
        };
    }
}
