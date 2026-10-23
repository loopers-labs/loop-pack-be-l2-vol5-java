package com.loopers.domain.order;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemReservationStatusTest {

    @ParameterizedTest
    @CsvSource({"NOT_RESERVED, HELD", "HELD, HELD"})
    void holdsOnceAndPreservesAlreadyHeldStatus(OrderItemReservationStatus current,
                                               OrderItemReservationStatus expected) {
        assertThat(current.hold()).isSameAs(expected);
    }

    @ParameterizedTest
    @CsvSource({"HELD, CONSUMED", "CONSUMED, CONSUMED"})
    void consumesOnceAndPreservesAlreadyConsumedStatus(OrderItemReservationStatus current,
                                                       OrderItemReservationStatus expected) {
        assertThat(current.consume()).isSameAs(expected);
    }

    @ParameterizedTest
    @CsvSource({"HELD, RELEASED", "RELEASED, RELEASED"})
    void releasesOnceAndPreservesAlreadyReleasedStatus(OrderItemReservationStatus current,
                                                       OrderItemReservationStatus expected) {
        assertThat(current.release()).isSameAs(expected);
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemReservationStatus.class, names = {"CONSUMED", "RELEASED"})
    void rejectsHoldingAfterFinalization(OrderItemReservationStatus current) {
        assertThatThrownBy(current::hold)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(current.name());
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemReservationStatus.class, names = {"NOT_RESERVED", "RELEASED"})
    void rejectsConsumptionWithoutHoldingOrAfterRelease(OrderItemReservationStatus current) {
        assertThatThrownBy(current::consume)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(current.name());
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemReservationStatus.class, names = {"NOT_RESERVED", "CONSUMED"})
    void rejectsReleaseWithoutHoldingOrAfterConsumption(OrderItemReservationStatus current) {
        assertThatThrownBy(current::release)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(current.name());
    }
}
