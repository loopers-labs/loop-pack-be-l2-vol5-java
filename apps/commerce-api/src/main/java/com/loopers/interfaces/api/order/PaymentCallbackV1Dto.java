package com.loopers.interfaces.api.order;

import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;

public class PaymentCallbackV1Dto {
    /** PG callback 본문. 결제 요청 응답과 같은 형식 (orderId 는 PG 주문 ID "ORDER-{id}"). 반영은 본문이 아니라 PG 거래 조회 결과로 한다. */
    public record CallbackRequest(String transactionKey, String orderId, Long amount, String status) {
        /** status 가 PENDING·SUCCESS·FAILED 가 아니면 UNKNOWN. 거래 조회 결과와 비교해 로그를 남기는 데 쓴다. */
        public PaymentGatewayResult toResult() {
            Outcome outcome = switch (status == null ? "" : status) {
                case "PENDING" -> Outcome.PENDING;
                case "SUCCESS" -> Outcome.SUCCESS;
                case "FAILED" -> Outcome.FAILED;
                default -> Outcome.UNKNOWN;
            };
            return new PaymentGatewayResult(outcome, transactionKey, amount);
        }
    }
}
