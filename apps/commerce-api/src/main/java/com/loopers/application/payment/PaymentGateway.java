package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentObservation;

import java.util.List;
import java.util.Optional;

/**
 * 외부 PG를 부르는 포트. 부르는 쪽은 Facade뿐이라 application에 둔다 — 도메인은 PG를 모르고 관찰 결과만 받아 판단한다
 * (docs/week5/design.md, ProductCache와 같은 배치). PG 호출은 항상 DB 트랜잭션 밖에서 한다.
 */
public interface PaymentGateway {

    /**
     * 내부 주문 번호를 PG에 보낼 주문 식별자로 바꾼다. 생성과 조회에 같은 값을 쓴다.
     */
    String providerOrderIdOf(Long orderId);

    /**
     * 거래 생성. 자동 재시도하지 않는다(INV-005). 결과는 예외가 아니라 세 갈래로 돌려준다 —
     * 호출자가 UNKNOWN을 처리하지 않고는 넘어갈 수 없게 하기 위해서다.
     */
    CreateResult create(CreatePaymentRequest request);

    /**
     * 거래 한 건을 조회한다. 없으면(404) 빈 값이고, 물어보지 못했으면 {@link PaymentGatewayUnavailableException}이다.
     */
    Optional<PaymentObservation> findByTransactionKey(String transactionKey, LookupRetry retry);

    /**
     * 같은 주문 식별자의 거래 목록. 0건은 "거래 없음"의 영구 증거가 아니다(INV-004).
     */
    List<PaymentObservation> findByProviderOrderId(String providerOrderId, LookupRetry retry);
}
