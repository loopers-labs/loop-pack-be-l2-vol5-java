package com.loopers.domain.pay.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAttemptTest {
    @Test
    @DisplayName("UUID를 정규화하고 같은 주문·키·금액·실효 조건만 같은 요청이다")
    void requestEquivalence() {
        String key = UUID.randomUUID().toString();
        PaymentScenario scenario = PaymentScenario.resolve(null, null);
        PaymentAttempt attempt = PaymentAttempt.create(42, key.toUpperCase(java.util.Locale.ROOT), 7000, scenario);
        assertThat(attempt.getAttemptKey()).isEqualTo(key);
        assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-0000000042");
        assertThat(attempt.isSameRequest(42, key, 7000, PaymentScenario.resolve(PaymentScenario.Preset.NORMAL_SUCCESS, null))).isTrue();
        assertThat(attempt.isSameRequest(43, key, 7000, scenario)).isFalse();
        assertThat(attempt.isSameRequest(42, key, 7001, scenario)).isFalse();
        assertThat(attempt.isSameRequest(42, key, 7000, PaymentScenario.resolve(PaymentScenario.Preset.LOST_SUCCESS, null))).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentAttempt.create(42, " " + key, 7000, scenario));
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentAttempt.create(42, "1-1-1-1-1", 7000, scenario));
    }

    @Test
    @DisplayName("성공 뒤 늦은 PENDING이나 조회 실패가 결과를 되돌리지 않고 종결 충돌은 보존한다")
    void terminalObservation() {
        PaymentAttempt pending = attempt().startDispatch().observe("LP-ORD-0000000042", 7000, "PG-1", PaymentAttempt.Outcome.PENDING);
        PaymentAttempt success = pending.observe(pending.getProviderOrderId(), 7000, "PG-1", PaymentAttempt.Outcome.SUCCESS);
        assertThat(success.observe(success.getProviderOrderId(), 7000, "PG-1", PaymentAttempt.Outcome.PENDING)).isSameAs(success);
        PaymentAttempt conflict = success.observe(success.getProviderOrderId(), 7000, "PG-1", PaymentAttempt.Outcome.FAILED);
        assertThat(conflict.getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(conflict.getTransactionKey()).isEqualTo("PG-1");
        assertThat(conflict.getConflict()).isEqualTo(PaymentAttempt.Conflict.TERMINAL_CONFLICT);
        PaymentAttempt wrongAmount = success.observe(success.getProviderOrderId(), 7001, "PG-1", PaymentAttempt.Outcome.SUCCESS);
        assertThat(wrongAmount.getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCESS);
        assertThat(wrongAmount.getConflict()).isEqualTo(PaymentAttempt.Conflict.IDENTITY_MISMATCH);
    }

    @Test
    @DisplayName("생성 전 거절은 거래 키 없이 저장하고 같은 거절은 멱등하게, 존재와의 모순은 충돌로 보존한다")
    void noEffectEvidence() {
        PaymentAttempt started = attempt().startDispatch();
        PaymentAttempt rejected = started.rejectBeforeCreate(started.getProviderOrderId(), 7000);
        assertThat(rejected.getOutcome()).isEqualTo(PaymentAttempt.Outcome.FAILED);
        assertThat(rejected.getRejection()).isEqualTo(PaymentAttempt.Rejection.NO_EFFECT);
        assertThat(rejected.getTransactionKey()).isNull();
        assertThat(rejected.rejectBeforeCreate(rejected.getProviderOrderId(), 7000)).isSameAs(rejected);
        PaymentAttempt contradiction = rejected.observe(rejected.getProviderOrderId(), 7000, "PG-1", PaymentAttempt.Outcome.SUCCESS);
        assertThat(contradiction.getRejection()).isEqualTo(PaymentAttempt.Rejection.NO_EFFECT);
        assertThat(contradiction.getTransactionKey()).isNull();
        assertThat(contradiction.getConflict()).isEqualTo(PaymentAttempt.Conflict.EXISTENCE_CONFLICT);
        PaymentAttempt pending = started.observe(started.getProviderOrderId(), 7000, "PG-1", PaymentAttempt.Outcome.PENDING);
        PaymentAttempt reversed = pending.rejectBeforeCreate(pending.getProviderOrderId(), 7000);
        assertThat(reversed.getOutcome()).isEqualTo(PaymentAttempt.Outcome.PENDING);
        assertThat(reversed.getTransactionKey()).isEqualTo("PG-1");
        assertThat(reversed.getConflict()).isEqualTo(PaymentAttempt.Conflict.EXISTENCE_CONFLICT);
    }

    private PaymentAttempt attempt() {
        return PaymentAttempt.create(42, UUID.randomUUID().toString(), 7000, PaymentScenario.resolve(null, null));
    }
}
