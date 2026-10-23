package com.loopers.application.ordering.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AutomaticPaymentExecutionTest {
    private final String key = UUID.randomUUID().toString();
    private final String id = UUID.randomUUID().toString();

    @ParameterizedTest
    @CsvSource({"1,800,1000,1200", "2,2400,3000,3600", "3,8000,10000,12000"})
    @DisplayName("각 회차의 재조회 간격에 지터 ±20%를 적용한다")
    void jitterBounds(int used, long low, long middle, long high) {
        assertThat(execution(used).retryDelayMillis(0, 0)).isEqualTo(low);
        assertThat(execution(used).retryDelayMillis(0.5, 0)).isEqualTo(middle);
        assertThat(execution(used).retryDelayMillis(1, 0)).isEqualTo(high);
    }

    @Test
    @DisplayName("남은 예산까지만 대기한 경우 다음 조회를 앞당기지 않고 중단하며 만료 뒤 대기를 시작하지 않는다")
    void truncatesWaitAndStopsNextRound() {
        var execution = execution(1);
        assertThat(execution.retryDelayMillis(0.5, 29_700_000_000L)).isEqualTo(300);
        assertThat(execution.expired(29_700_000_001L)).isTrue();
        assertThatThrownBy(() -> execution(1).retryDelayMillis(0.5, 30_000_000_000L))
            .isInstanceOf(AutomaticPaymentBudgetExceededException.class);
    }

    @Test
    @DisplayName("회차 commit 미확인은 원래 실행의 이전 또는 예정 회차만 비교하고 새 소유자는 사용하지 않는다")
    void fencesUncertainConsumption() {
        var execution = execution(0);
        var old = execution.snapshot();
        var reserved = execution.beginConsumption();
        assertThat(execution.snapshotFor(old)).isSameAs(old);
        assertThat(execution.snapshotFor(reserved)).isSameAs(reserved);
        assertThat(execution.snapshotFor(new PaymentRecoveryTask.Snapshot(key, PaymentRecoveryTask.State.RUNNING,
            UUID.randomUUID().toString(), 1))).isNull();
        execution.consumptionCommitted();
        assertThat(execution.snapshot().usedRounds()).isEqualTo(1);
        assertThat(execution.snapshotFor(old)).isNull();
    }

    private AutomaticPaymentExecution execution(int used) {
        return new AutomaticPaymentExecution(new PaymentRecoveryTask.Snapshot(key, PaymentRecoveryTask.State.RUNNING, id, used), 0);
    }
}
