package com.loopers.payment.application.port.out;

import java.util.List;
import java.util.Optional;

/**
 * PG 거래 생성·조회 (plan.md 7장). 생성은 재시도하지 않고, 결과를 모르면 조회로만 확인한다 (ADR-W5-01·03).
 */
public interface PaymentGatewayPort {

    /**
     * 거래 생성 1회.
     *
     * @throws PgRejectedException     PG가 거래를 만들지 않고 거절했다 (효과 0건)
     * @throws PgNotSentException      요청이 나가지 않은 게 확실하다 (회로 OPEN, 연결 실패)
     * @throws PgUnknownResultException 보냈을 수 있으나 결과를 모른다 (읽기 timeout, 5xx, 응답 해석 불가)
     */
    PgTransaction request(PgPaymentRequest request);

    /**
     * 거래 한 건. PG가 "없음"이라 답하면 빈 값 — 영구적인 증거는 아니다 (plan.md 3-2).
     *
     * @throws PgLookupUnavailableException 조회하지 못했다 (timeout, 5xx, 회로 OPEN)
     */
    Optional<PgTransaction> findByTransactionKey(String transactionKey);

    /**
     * 같은 주문의 거래 목록. 0건·여러 건 모두 그대로 돌려준다.
     *
     * @throws PgLookupUnavailableException 조회하지 못했다 (timeout, 5xx, 회로 OPEN)
     */
    List<PgTransaction> findByProviderOrderId(String providerOrderId);
}
