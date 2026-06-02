package com.northwood.shared.infrastructure.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit coverage for {@link DltRedriver#isDeterministic} — the CHECK-vs-FK split
 * that decides whether a redrive failure is poison (park now) or a transient /
 * prerequisite-timing case the redrive tier exists to recover (keep retrying).
 */
class DltRedriverClassificationTest {

    private static DataIntegrityViolationException dataIntegrity(String sqlState) {
        return new DataIntegrityViolationException("constraint", new SQLException("violation", sqlState));
    }

    @Test void check_violation_is_deterministic() {
        // 23514 = the reported bug (invoiced_amount <= total_amount CHECK).
        assertThat(DltRedriver.isDeterministic(dataIntegrity("23514"))).isTrue();
    }

    @Test void not_null_and_data_exception_are_deterministic() {
        assertThat(DltRedriver.isDeterministic(dataIntegrity("23502"))).isTrue();   // not-null
        assertThat(DltRedriver.isDeterministic(dataIntegrity("22003"))).isTrue();   // numeric overflow
    }

    @Test void foreign_key_violation_is_NOT_deterministic() {
        // 23503: prerequisite row may arrive on a later redrive — keep retrying.
        assertThat(DltRedriver.isDeterministic(dataIntegrity("23503"))).isFalse();
    }

    @Test void unique_violation_is_NOT_deterministic() {
        assertThat(DltRedriver.isDeterministic(dataIntegrity("23505"))).isFalse();
    }

    @Test void malformed_payload_and_shape_bugs_are_deterministic() {
        assertThat(DltRedriver.isDeterministic(new tools.jackson.core.JacksonException("bad json") {})).isTrue();
        assertThat(DltRedriver.isDeterministic(new NullPointerException())).isTrue();
        assertThat(DltRedriver.isDeterministic(new ClassCastException())).isTrue();
    }

    @Test void illegal_state_stays_retryable() {
        // The Assert.state idiom covers "no saga row yet" prerequisite-timing too,
        // so it must NOT be parked on the first redrive.
        assertThat(DltRedriver.isDeterministic(new IllegalStateException("No P2P saga yet"))).isFalse();
    }

    @Test void deterministic_cause_found_deep_in_the_chain() {
        Throwable wrapped = new RuntimeException("listener failed", dataIntegrity("23514"));
        assertThat(DltRedriver.isDeterministic(wrapped)).isTrue();
    }

    @Test void unknown_failure_is_not_deterministic() {
        assertThat(DltRedriver.isDeterministic(new RuntimeException("broker timeout"))).isFalse();
    }
}
