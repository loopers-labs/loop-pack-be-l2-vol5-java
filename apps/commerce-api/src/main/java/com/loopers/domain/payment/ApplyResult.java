package com.loopers.domain.payment;

/**
 * 결제 시도에 관찰·응답을 반영한 결말. 호출자(결과 반영 트랜잭션)는 이 값을 보고 주문·재고 효과를 낸다 —
 * 효과는 SUCCEEDED·FAILED, 즉 미종결 → 종결 전이가 일어난 경우에만 있다(INV-002).
 */
public enum ApplyResult {
    /** 이번 반영으로 SUCCESS로 전이했다. 주문 확정, 예약 소비. */
    SUCCEEDED,
    /** 이번 반영으로 FAILED로 전이했다. 주문 실패, 예약 한 번 반환. */
    FAILED,
    /** 상태가 종결로 바뀌지 않았다(미종결 유지, 또는 이미 같은 결과로 종결). 효과 없음. */
    NO_EFFECT,
    /** 기록된 결제 상태와 PG 관찰 결과가 어긋난다. 상태는 그대로 두고 충돌로 기록한다(INV-003). */
    CONFLICT;

    public boolean hasEffect() {
        return this == SUCCEEDED || this == FAILED;
    }
}
