package com.loopers.domain.payment;

/**
 * 수신함 행의 처리 상태(docs/week5/design.md D-021).
 */
public enum CallbackInboxStatus {
    /** 받아서 저장만 했다. 다음 회차에 처리한다. */
    RECEIVED,
    /** PG 조회에 실패했다. 다음 회차에 다시 꺼낸다(D-042). */
    RETRY,
    /** 조회 결과를 반영했다(전이·변화 없음·충돌 기록 모두). */
    DONE,
    /** 키에 맞는 결제 시도가 없다. 다시 처리하지 않는다 — 진짜 callback이면 시도가 먼저 commit돼 있다(INV-005). */
    UNMATCHED,
    /** 조회가 3번 실패했다. 그 결제는 일반 미종결 결제로서 스케줄러가 계속 확인한다. */
    GAVE_UP;

    public boolean isPending() {
        return this == RECEIVED || this == RETRY;
    }
}
