package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.RefundCase;

public interface RefundOutcomeRecorder {
    // 돈의 상태가 환불됨·청구 없음으로 끝난 것을 결론을 낸 주체별로 센다. 청구 없음의 주체는 PG 조회면 SYSTEM, 관리자 기록이면 PERSON이다.
    void recordOutcome(Result result, RefundCase.RefundActor actor);

    // 담당이 사람으로 바뀐 것을 사유(RefundCase의 사유 코드)별로 센다.
    void recordPersonAssigned(String reason);

    // 마지막 확인 결과를 분류별로 센다.
    void recordCheck(RefundCase.CheckOutcome outcome);

    enum Result {
        REFUNDED, NO_CHARGE
    }
}
