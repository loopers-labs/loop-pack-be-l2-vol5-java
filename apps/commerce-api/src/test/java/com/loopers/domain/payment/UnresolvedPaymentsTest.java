package com.loopers.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedPaymentsTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-25T12:00:00Z");

    @DisplayName("가장 오래 기다린 고객의 대기시간은 현재 시각에서 그 상태의 가장 오래된 생성 시각을 뺀 초다.")
    @Test
    void oldestAge_isSecondsSinceOldestCreatedAt() {
        // arrange
        UnresolvedPayments unresolved = new UnresolvedPayments(List.of(
            new UnresolvedPaymentGroup(PaymentAttemptStatus.UNKNOWN, 3, NOW.minusSeconds(650))
        ));

        // act & assert
        assertThat(unresolved.countOf(PaymentAttemptStatus.UNKNOWN)).isEqualTo(3);
        assertThat(unresolved.oldestAgeSeconds(PaymentAttemptStatus.UNKNOWN, NOW)).isEqualTo(650);
    }

    @DisplayName("미확인 시도가 없는 상태는 건수와 대기시간이 모두 0 이다. 해소된 뒤 지난 값이 남아 알람이 풀리지 않는 일을 막는다.")
    @Test
    void absentStatus_reportsZero() {
        // arrange
        UnresolvedPayments unresolved = new UnresolvedPayments(List.of(
            new UnresolvedPaymentGroup(PaymentAttemptStatus.PENDING, 1, NOW.minusSeconds(5))
        ));

        // act & assert
        assertThat(unresolved.countOf(PaymentAttemptStatus.UNKNOWN)).isZero();
        assertThat(unresolved.oldestAgeSeconds(PaymentAttemptStatus.UNKNOWN, NOW)).isZero();
    }
}
