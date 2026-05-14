package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import com.northwood.shared.infrastructure.saga.SagaStateInvariantCheck;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the saga-state invariant for {@code make_to_order_saga}.
 * Boot fails if any state in {@link MakeToOrderSaga#ALL_STATES} is missing
 * from the schema CHECK on {@code manufacturing.make_to_order_saga.saga_state}.
 */
@Configuration
public class ManufacturingSagaInvariantsConfig {

    @Bean
    public SagaStateInvariantCheck makeToOrderSagaStates() {
        return new SagaStateInvariantCheck() {
            @Override public String schemaName() { return "manufacturing"; }
            @Override public String tableName()  { return "make_to_order_saga"; }
            @Override public Set<String> codeStates() { return MakeToOrderSaga.ALL_STATES; }
        };
    }
}
