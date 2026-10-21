package com.loopers.payment.application.port.in;

import java.time.ZonedDateTime;

/**
 * 스케줄러가 부르는 복구 입구 (plan.md 5-1). 정리는 "보내지 못한 일"을, reconcile은 "결과를 모르는 일"을 다룬다. 둘 다 결제를 생성하지 않는다.
 */
public interface PaymentRecoveryUseCase {

    /**
     * 정리 (ADR-W5-03): 30초 넘게 아무도 발송 권한을 얻지 않은 결제는 FAILED(NOT_SENT)로 닫고 재고를 반환한다.
     * 30초 넘게 발송 권한만 쥐고 결과가 없는 결제는 보냈을 수 있으므로 UNKNOWN으로 넘긴다 (다시 보내지 않는다).
     */
    StaleDispatchResult closeStaleDispatches(ZonedDateTime now);

    /**
     * reconcile: 다음 조회 시각이 된 PENDING·UNKNOWN 결제를 PG 조회로 확인한다. 확인한 결제 수를 돌려준다.
     */
    int reconcileDue(ZonedDateTime now);

    record StaleDispatchResult(int closedNotSent, int markedUnknown) {}
}
