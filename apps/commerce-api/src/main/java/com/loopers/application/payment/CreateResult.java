package com.loopers.application.payment;

/**
 * 거래 생성 요청의 결과(docs/week5/pg-contract.md 2번).
 */
public sealed interface CreateResult {

    /** 202 PENDING. 거래가 생겼고 transactionKey를 안다. */
    record Accepted(String transactionKey) implements CreateResult {}

    /** 정확한 조건의 422. 거래를 만들지 않았고 앞으로도 만들지 않는다 — 효과 0건이 보장된 유일한 응답이다. */
    record RejectedNoEffect() implements CreateResult {}

    /**
     * 그 밖의 모든 경우 — timeout(연결·응답 구분 없이, D-025), 5xx, 본문 없는 422, 다른 code, 깨진 JSON,
     * 식별·금액 불일치. 거래가 생겼는지 모른다. 실패가 아니다(INV-004).
     */
    record Unknown(String reason) implements CreateResult {}
}
