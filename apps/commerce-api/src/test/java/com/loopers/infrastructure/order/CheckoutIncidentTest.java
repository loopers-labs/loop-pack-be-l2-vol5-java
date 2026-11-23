package com.loopers.infrastructure.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;

/**
 * W10 AUTO wrapper(checkout-incident §6.1). 공통 시나리오를 사람 대기·모니터링 컨테이너 없이 실행한다.
 * 전용 Gradle 작업 checkoutIncidentTest 에서만 실행되고 일반 test 에서는 제외된다.
 */
@Timeout(420)
@DisplayName("W10 결제 사고 20주문 AUTO 시나리오")
class CheckoutIncidentTest {

    /** 정리 단계가 wrapper 한도 안에서 끝나도록 업무 진행에 쓸 전체 기한. */
    private static final Duration SCENARIO_BUDGET = Duration.ofSeconds(360);
    private static final String OPERATOR = "AUTO";

    @Test
    void baselineFaultRecoveryAndDownstream() throws Exception {
        IncidentDeadline deadline = IncidentDeadline.after(SCENARIO_BUDGET);
        IncidentEvidenceWriter writer = IncidentEvidenceWriter.openRun(IncidentEvidenceWriter.DEFAULT_ROOT,
            IncidentCodeReference.capture(Path.of("")), IncidentMode.AUTO, OPERATOR, CheckoutIncidentScenario.CONFIRMED_AT);
        System.out.println("W10_AUTO runId=" + writer.runId() + " runDir=" + writer.runDir());
        IncidentCheckpoint checkpoint = IncidentCheckpoint.create(writer);
        new CheckoutIncidentScenario().run(IncidentMode.AUTO, new CheckoutIncidentScenario.ScenarioInputs(OPERATOR),
            writer, checkpoint, deadline);
    }
}
