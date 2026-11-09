package com.loopers.domain.payment;

/**
 * 외부 PG에 결제를 요청하고 거래를 조회하는 약속이다 (docs/week5/pg-contract.md 4절).
 * HTTP·JSON·timeout은 구현(infrastructure)만 알고, 호출자는 예외 대신 관찰 결과 타입을 받는다.
 * "실패"와 "결과를 모름"을 예외 종류로 추측하지 않게 하기 위해서다.
 */
public interface PaymentGateway {

    /**
     * 거래 생성을 한 번 요청한다. 어떤 층에서도 다시 보내지 않는다.
     * PG는 호출자 멱등 키를 받지 않으므로, 다시 보내면 곧 두 번째 거래다.
     */
    PgCreateResult requestPayment(PgPaymentRequest request);

    /** 거래 한 건을 조회한다. 결과는 0건 또는 1건이다. */
    PgLookupResult findTransaction(String transactionKey);

    /** 같은 주문 번호의 거래를 모두 조회한다. 결과는 0건 이상이다. */
    PgLookupResult findTransactionsByOrder(String providerOrderId);
}
