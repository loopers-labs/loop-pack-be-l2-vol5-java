package com.loopers.pgsimulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("수동 실행 금액 규칙은 amount % 3 으로 생성 결과를 정한다.")
class AmountModuloOutcomePolicyTest {

    private final AmountModuloOutcomePolicy policy = new AmountModuloOutcomePolicy();

    @DisplayName("나머지 0 은 PENDING 뒤 SUCCESS, 1 은 즉시 SUCCESS, 2 는 PENDING 뒤 FAILED 이며 422 거절은 고르지 않는다.")
    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource({
        "6000, PENDING_THEN_SUCCESS",
        "7000, IMMEDIATE_SUCCESS",
        "8000, PENDING_THEN_FAILED",
        "3, PENDING_THEN_SUCCESS",
        "1, IMMEDIATE_SUCCESS",
        "2, PENDING_THEN_FAILED",
        "9223372036854775807, IMMEDIATE_SUCCESS"
    })
    void decidesByAmountModulo(long amount, CreateOutcome expected) {
        assertThat(policy.decide(new CreateAttempt("LP-ORD-0000000001", amount))).isEqualTo(expected);
    }
}
