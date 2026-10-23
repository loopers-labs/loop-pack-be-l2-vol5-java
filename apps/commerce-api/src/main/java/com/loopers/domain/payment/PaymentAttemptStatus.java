package com.loopers.domain.payment;

/**
 * 결제 시도의 상태 — 외부 결제 사실에 대해 "우리가 지금 무엇을 아는가"를 나타낸다(docs/week5/design.md 2번 섹션, D-017).
 * REQUESTED·UNKNOWN은 transactionKey를 몰라 목록 조회, PENDING은 단건 조회로 확인한다.
 */
public enum PaymentAttemptStatus {
    REQUESTED,
    PENDING,
    UNKNOWN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
