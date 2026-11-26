package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import com.loopers.domains.payment.domain.RefundCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

// 모든 label 조합을 시작 시 등록해 아직 0인 값도 시계열로 보이게 한다. 주문 수만큼 늘어나지 않도록 orderId는 태그로 넣지 않는다.
// label 값은 알림 규칙·대시보드가 쓰는 소문자다(10주차 5b 계획 4절).
@Component
public class MicrometerRefundOutcomeRecorder implements RefundOutcomeRecorder {
    static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";
    static final String ASSIGNED_METRIC = "commerce.payment.refund_person_assigned";
    private static final String OUTCOME_DESCRIPTION = "환불 확인 대상의 돈의 상태가 환불됨·청구 없음으로 끝난 횟수, result·actor별. "
        + "commit 뒤에만 센다.";
    private static final String ASSIGNED_DESCRIPTION = "환불 확인 대상의 담당이 사람으로 바뀐 횟수, reason별. commit 뒤에만 센다.";
    private static final List<String> REASONS = List.of(RefundCase.HANDOFF_DEADLINE, RefundCase.TRANSACTION_MISMATCH,
        RefundCase.MULTIPLE_TRANSACTIONS, RefundCase.REFUND_REJECTED, RefundCase.RESULT_REVERSED);

    private final Map<String, Counter> outcomes;
    private final Map<String, Counter> assignments;

    public MicrometerRefundOutcomeRecorder(MeterRegistry meterRegistry) {
        Map<String, Counter> registeredOutcomes = new HashMap<>();
        for (Result result : Result.values()) {
            for (RefundCase.RefundActor actor : RefundCase.RefundActor.values()) {
                registeredOutcomes.put(outcomeKey(result, actor), Counter.builder(OUTCOME_METRIC)
                    .description(OUTCOME_DESCRIPTION)
                    .tag("result", label(result.name()))
                    .tag("actor", label(actor.name()))
                    .register(meterRegistry));
            }
        }
        Map<String, Counter> registeredAssignments = new HashMap<>();
        for (String reason : REASONS) {
            registeredAssignments.put(reason, Counter.builder(ASSIGNED_METRIC)
                .description(ASSIGNED_DESCRIPTION)
                .tag("reason", label(reason))
                .register(meterRegistry));
        }
        this.outcomes = Map.copyOf(registeredOutcomes);
        this.assignments = Map.copyOf(registeredAssignments);
    }

    @Override
    public void recordOutcome(Result result, RefundCase.RefundActor actor) {
        outcomes.get(outcomeKey(result, actor)).increment();
    }

    // 사유는 RefundCase의 상수만 온다. 모르는 사유를 새 시계열로 만들지 않고 드러낸다.
    @Override
    public void recordPersonAssigned(String reason) {
        Counter counter = assignments.get(reason);
        if (counter == null) {
            throw new IllegalArgumentException("모르는 사람 담당 사유입니다: " + reason);
        }
        counter.increment();
    }

    private static String outcomeKey(Result result, RefundCase.RefundActor actor) {
        return result.name() + "/" + actor.name();
    }

    private static String label(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
