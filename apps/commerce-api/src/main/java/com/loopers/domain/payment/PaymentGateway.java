package com.loopers.domain.payment;

/**
 * 로컬 테스트 PG 포트. HTTP 응답이 아니라 "관찰 결과"를 돌려준다.
 * HTTP 요청·응답 매핑은 infrastructure 구현(3단계)이 맡는다.
 */
public interface PaymentGateway {

    /**
     * 거래 생성을 1회 요청한다. 자동 재시도는 하지 않는다.
     */
    PaymentCreateResult create(PaymentCreateCommand command);

    /**
     * 같은 주문(providerOrderId)의 거래 목록 조회. 빈 목록(FOUND + 0건)과 조회 실패(UNAVAILABLE)를 구분한다.
     */
    PaymentLookupResult findAllByOrderId(String providerOrderId);

    /**
     * 거래 단건 조회. 목록 조회와 같은 결과 타입이다. 200 은 FOUND + 1건, 404 는 FOUND + 0건, 조회 실패(5xx·잘못된 JSON·timeout)는 UNAVAILABLE.
     * Optional 로 두면 "거래 없음"과 "확인 불가"가 구분되지 않는다 (설계 7-3).
     */
    PaymentLookupResult findByTransactionKey(String transactionKey);
}