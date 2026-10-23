package com.loopers.application.order;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** PG 주문 ID "ORDER-{id}" ↔ 우리 주문 ID. */
class PgOrderIdTest {

    @DisplayName("우리 주문 ID 를 \"ORDER-{id}\" 로 바꾸고, 다시 읽으면 같은 ID 다.")
    @Test
    void roundTrip() {
        assertThat(PgOrderId.of(42L)).isEqualTo("ORDER-42");
        assertThat(PgOrderId.parse(PgOrderId.of(42L))).isEqualTo(42L);
    }

    @DisplayName("[ER-05 ORDER_NOT_FOUND] 형식이 아니면 없는 주문으로 본다 (callback 으로 잘못된 ID 가 온 경우).")
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "42", "ORDER-", "ORDER-abc", "order-42", "PAY-42"})
    void parse_throwsOrderNotFound_whenMalformed(String pgOrderId) {
        assertThrowsErrorType(() -> PgOrderId.parse(pgOrderId), ErrorType.ORDER_NOT_FOUND);
    }
}
