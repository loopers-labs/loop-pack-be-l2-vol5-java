package com.loopers.support.pg;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentOutcome;
import com.loopers.domain.payment.ProviderOrderId;

/**
 * 테스트가 주문을 PG로 결제할 때 쓰는 도우미. 카드 값은 합성 값만 쓴다.
 * 주문마다 결제 시도 키가 달라야 하므로 키는 주문 id로 만든다.
 */
public final class PaymentTestSupport {

    public static final String CARD_TYPE = "TEST";
    public static final String CARD_NO = "0000-0000-0000-0000";

    private PaymentTestSupport() {
    }

    public static String attemptKeyOf(Long orderId) {
        return "attempt-" + orderId;
    }

    /** 확정 요청(POST /confirm)의 본문. */
    public static String confirmBody(String attemptKey) {
        return "{\"paymentAttemptKey\":\"" + attemptKey + "\",\"cardType\":\"" + CARD_TYPE + "\",\"cardNo\":\"" + CARD_NO + "\"}";
    }

    public static String confirmBodyOf(Long orderId) {
        return confirmBody(attemptKeyOf(orderId));
    }

    /** 주문의 PG 결제를 시작한다. 결과는 처리 중(PAYMENT_PENDING)이다. */
    public static PaymentOutcome startPayment(PaymentFacade paymentFacade, Long orderId, Long userId) {
        return paymentFacade.confirm(orderId, userId, attemptKeyOf(orderId), CARD_TYPE, CARD_NO);
    }

    /** PG가 이 주문의 거래를 성공으로 끝낸 것으로 만든 뒤, 조회로 확인해 주문을 CONFIRMED로 마무리한다. */
    public static PaymentOutcome startAndSucceed(PaymentFacade paymentFacade, TestPgServer pg, Long orderId, Long userId) {
        startPayment(paymentFacade, orderId, userId);
        completeAt(pg, orderId, TestPgStatus.SUCCESS);
        return paymentFacade.reconcile(orderId, userId);
    }

    /** PG에 있는 이 주문의 거래 하나를 최종 상태로 바꾼다. 거래가 정확히 하나여야 한다. */
    public static void completeAt(TestPgServer pg, Long orderId, TestPgStatus status) {
        pg.complete(onlyTransactionOf(pg, orderId).transactionKey(), status);
    }

    public static TestPgTransaction onlyTransactionOf(TestPgServer pg, Long orderId) {
        var transactions = pg.transactionsOf(ProviderOrderId.from(orderId));
        if (transactions.size() != 1) {
            throw new IllegalStateException("주문 " + orderId + "의 PG 거래가 정확히 1건이어야 한다: " + transactions.size());
        }
        return transactions.get(0);
    }
}
