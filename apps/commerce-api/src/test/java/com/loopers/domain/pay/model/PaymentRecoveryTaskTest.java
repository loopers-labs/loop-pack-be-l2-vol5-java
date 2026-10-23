package com.loopers.domain.pay.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRecoveryTaskTest {
    @Test
    @DisplayName("인계는 실행 ID만 교체하고 소비 횟수는 유지하며 옛 실행과 예산 초과를 거절한다")
    void ownershipAndBudget() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        String oldId = UUID.randomUUID().toString();
        String newId = UUID.randomUUID().toString();
        PaymentRecoveryTask first = PaymentRecoveryTask.create(UUID.randomUUID().toString()).claim(oldId, now)
                .consumeRound(oldId, now);
        PaymentRecoveryTask handedOver = first.handover(newId, now);
        assertThat(handedOver.getUsedRounds()).isEqualTo(1);
        assertThatThrownBy(() -> handedOver.consumeRound(oldId, now)).isInstanceOf(DomainException.class);
        PaymentRecoveryTask exhausted = handedOver.consumeRound(newId, now).consumeRound(newId, now).consumeRound(newId, now);
        assertThat(exhausted.getUsedRounds()).isEqualTo(4);
        assertThatThrownBy(() -> exhausted.consumeRound(newId, now)).isInstanceOf(DomainException.class);
        PaymentRecoveryTask manual = exhausted.requireManual(newId, PaymentRecoveryTask.Reason.RETRY_EXHAUSTED, now);
        assertThat(manual.getState()).isEqualTo(PaymentRecoveryTask.State.MANUAL_REQUIRED);
        assertThat(manual.applied(now).getState()).isEqualTo(PaymentRecoveryTask.State.APPLIED);
    }
}
