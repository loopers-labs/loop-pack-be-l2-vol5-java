package com.loopers.domain.payment;

/** 거래 생성 요청의 관찰 결과 (docs/week5/pg-contract.md 4-1절). */
public sealed interface PgCreateResult {

    /** 거래가 생겼다. 결제 결과는 아직 모른다. 최종 결과는 조회로만 확인한다. */
    record Accepted(String transactionKey) implements PgCreateResult {
    }

    /** PG가 거래를 만들기 전에 거절했고, 이 요청의 거래를 나중에도 만들지 않는다는 것이 계약으로 보장된다. */
    record RejectedNoEffect() implements PgCreateResult {
    }

    /** 거래가 생겼는지 모른다. 실패가 아니다. 예약을 유지하고 기존 거래 조회로 확인한다. */
    record Unknown(UnknownReason reason) implements PgCreateResult {
    }
}
