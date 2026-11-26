package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import com.loopers.domains.payment.domain.RefundCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 이름·label 값은 커밋된 알림 규칙·대시보드(102c864)가 쓰는 것으로 고정한다(10주차 5b 계획 4절, S7).
class MicrometerRefundOutcomeRecorderTest {
    private static final String OUTCOME = "commerce.payment.refund_outcome";
    private static final String ASSIGNED = "commerce.payment.refund_person_assigned";
    private static final String CHECK = "commerce.payment.refund_check";
    private static final List<String> CHECK_OUTCOMES = List.of("pg_unreachable", "pg_error", "apply_failed", "mismatch",
        "pending", "empty", "cancel_unknown", "cancel_rejected", "ok");
    private static final List<String> RESULTS = List.of("refunded", "no_charge");
    private static final List<String> ACTORS = List.of("system", "person", "external");
    private static final List<String> REASONS = List.of("handoff_deadline", "transaction_mismatch", "multiple_transactions",
        "refund_rejected", "result_reversed");

    @Test
    @DisplayName("시작하면 소문자 result×actor 6개 결론 counter와 소문자 사유 5개 사람 담당 counter가 0으로 있고 주문 ID 같은 다른 label은 없다")
    void registersLowercaseLabelsAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MicrometerRefundOutcomeRecorder(registry);

        assertThat(registry.find(OUTCOME).counters()).hasSize(6)
            .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("result", "actor"));
        RESULTS.forEach(result -> ACTORS.forEach(actor ->
            assertThat(outcome(registry, result, actor)).as(result + "/" + actor).isZero()));
        assertThat(registry.find(ASSIGNED).counters()).hasSize(5)
            .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("reason"));
        REASONS.forEach(reason -> assertThat(assigned(registry, reason)).as(reason).isZero());
    }

    @Test
    @DisplayName("환불됨(PERSON)과 청구 없음(SYSTEM)을 기록하면 그 두 결론 counter만 1씩 는다")
    void countsOnlyRecordedOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundOutcomeRecorder recorder = new MicrometerRefundOutcomeRecorder(registry);

        recorder.recordOutcome(RefundOutcomeRecorder.Result.REFUNDED, RefundCase.RefundActor.PERSON);
        recorder.recordOutcome(RefundOutcomeRecorder.Result.NO_CHARGE, RefundCase.RefundActor.SYSTEM);

        assertThat(outcome(registry, "refunded", "person")).isEqualTo(1.0);
        assertThat(outcome(registry, "no_charge", "system")).isEqualTo(1.0);
        assertThat(registry.find(OUTCOME).counters()).extracting(Counter::count).containsOnly(0.0, 1.0)
            .filteredOn(count -> count == 1.0).hasSize(2);
    }

    @Test
    @DisplayName("사유 RESULT_REVERSED로 사람 담당을 기록하면 result_reversed counter만 1 는다")
    void countsOnlyRecordedReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundOutcomeRecorder recorder = new MicrometerRefundOutcomeRecorder(registry);

        recorder.recordPersonAssigned(RefundCase.RESULT_REVERSED);

        assertThat(assigned(registry, "result_reversed")).isEqualTo(1.0);
        REASONS.stream().filter(reason -> !reason.equals("result_reversed"))
            .forEach(reason -> assertThat(assigned(registry, reason)).as(reason).isZero());
    }

    @Test
    @DisplayName("C9: 시작하면 소문자 outcome 9개 마지막 확인 counter가 0으로 있어 첫 apply_failed도 증가로 잡히고 outcome 외 label은 없다")
    void registersCheckOutcomesAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MicrometerRefundOutcomeRecorder(registry);

        assertThat(registry.find(CHECK).counters()).hasSize(9)
            .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("outcome"));
        CHECK_OUTCOMES.forEach(outcome -> assertThat(check(registry, outcome)).as(outcome).isZero());
    }

    @Test
    @DisplayName("C9: APPLY_FAILED를 기록하면 apply_failed counter만 1 는다")
    void countsOnlyRecordedCheckOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundOutcomeRecorder recorder = new MicrometerRefundOutcomeRecorder(registry);

        recorder.recordCheck(RefundCase.CheckOutcome.APPLY_FAILED);

        assertThat(check(registry, "apply_failed")).isEqualTo(1.0);
        CHECK_OUTCOMES.stream().filter(outcome -> !outcome.equals("apply_failed"))
            .forEach(outcome -> assertThat(check(registry, outcome)).as(outcome).isZero());
    }

    private static double check(SimpleMeterRegistry registry, String outcome) {
        Counter counter = registry.find(CHECK).tag("outcome", outcome).counter();
        return counter == null ? -1 : counter.count();
    }

    private static double outcome(SimpleMeterRegistry registry, String result, String actor) {
        Counter counter = registry.find(OUTCOME).tag("result", result).tag("actor", actor).counter();
        return counter == null ? -1 : counter.count();
    }

    private static double assigned(SimpleMeterRegistry registry, String reason) {
        Counter counter = registry.find(ASSIGNED).tag("reason", reason).counter();
        return counter == null ? -1 : counter.count();
    }
}
