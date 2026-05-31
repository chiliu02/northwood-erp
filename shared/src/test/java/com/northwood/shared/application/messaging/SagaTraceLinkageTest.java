package com.northwood.shared.application.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SagaTraceLinkageTest {

    @Test void span_link_links_only() {
        assertThat(SagaTraceLinkage.SPAN_LINK.restoresParent()).isFalse();
        assertThat(SagaTraceLinkage.SPAN_LINK.addsLink()).isTrue();
    }

    @Test void parent_child_reparents_only() {
        assertThat(SagaTraceLinkage.PARENT_CHILD.restoresParent()).isTrue();
        assertThat(SagaTraceLinkage.PARENT_CHILD.addsLink()).isFalse();
    }

    @Test void both_reparents_and_links() {
        assertThat(SagaTraceLinkage.BOTH.restoresParent()).isTrue();
        assertThat(SagaTraceLinkage.BOTH.addsLink()).isTrue();
    }

    @Test void off_does_neither() {
        assertThat(SagaTraceLinkage.OFF.restoresParent()).isFalse();
        assertThat(SagaTraceLinkage.OFF.addsLink()).isFalse();
    }

    @Test void from_property_accepts_hyphen_underscore_and_case() {
        assertThat(SagaTraceLinkage.fromProperty("span-link")).isEqualTo(SagaTraceLinkage.SPAN_LINK);
        assertThat(SagaTraceLinkage.fromProperty("PARENT_CHILD")).isEqualTo(SagaTraceLinkage.PARENT_CHILD);
        assertThat(SagaTraceLinkage.fromProperty(" Both ")).isEqualTo(SagaTraceLinkage.BOTH);
        assertThat(SagaTraceLinkage.fromProperty("off")).isEqualTo(SagaTraceLinkage.OFF);
    }

    @Test void from_property_rejects_unknown() {
        assertThatThrownBy(() -> SagaTraceLinkage.fromProperty("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("northwood.tracing.saga-linkage");
    }
}
