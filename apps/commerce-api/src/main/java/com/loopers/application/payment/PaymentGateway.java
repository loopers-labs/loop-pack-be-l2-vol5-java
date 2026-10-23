package com.loopers.application.payment;

import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusCheckResult;

/**
 * 외부 결제 시스템(PG)과의 경계. HTTP·JSON·timeout 같은 전송 지식은 구현({@code infrastructure.payment})이 숨기고,
 * application 의 타입({@code payment.pg} 패키지)으로만 입출력한다.
 */
public interface PaymentGateway {

    /** 결제를 요청한다. 자동으로 재시도하지 않고, 예외 없이 모든 결과를 {@link PgPaymentRequestResult} 로 돌려준다. */
    PgPaymentRequestResult requestPayment(PgPaymentRequestCommand request);

    /** 거래 키로 PG 에 결제 상태를 묻는다. */
    PgPaymentStatusCheckResult checkPaymentStatusByTransactionKey(String transactionKey);

    /** 주문 식별자로 PG 에 결제 상태를 묻는다. 같은 주문의 거래가 0건 이상일 수 있다. */
    PgPaymentStatusCheckResult checkPaymentStatusByOrder(String providerOrderId);
}
