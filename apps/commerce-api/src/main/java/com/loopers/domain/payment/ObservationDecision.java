package com.loopers.domain.payment;

/**
 * PaymentModel.recordObservation 의 판정. 결제·확인 정보는 이미 바뀌었고, 결과 반영 TX 가 이 판정으로
 * 주문 전이·재고 반환·충돌 요약 저장을 이어서 한다.
 */
public record ObservationDecision(Effect effect, PaymentConflictCode conflictCode, ConflictEvidence evidence) {

    public enum Effect {
        /** 미종결 결제를 SUCCESS 로 종결. 주문을 확정하고 재고는 더 차감하지 않는다. */
        CONFIRM,
        /** 미종결 결제를 FAILED 로 종결. 주문을 실패시키고 고정 품목 수량을 한 번 반환한다. */
        RELEASE,
        PENDING,
        UNAVAILABLE,
        /** 이미 종결된 결제에 추가 업무 효과가 없음(같은 종결 재확인·무시할 늦은 관찰). */
        FINAL_UNCHANGED,
        /** 업무 상태·재고를 유지하고 충돌 요약을 저장한다. */
        CONFLICT
    }

    static ObservationDecision of(Effect effect) {
        return new ObservationDecision(effect, null, null);
    }

    static ObservationDecision conflict(PaymentConflictCode code, ConflictEvidence evidence) {
        return new ObservationDecision(Effect.CONFLICT, code, evidence);
    }
}
