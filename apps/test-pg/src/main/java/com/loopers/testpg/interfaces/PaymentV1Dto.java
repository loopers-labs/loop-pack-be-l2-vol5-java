package com.loopers.testpg.interfaces;

import com.loopers.testpg.application.CreateResult;
import com.loopers.testpg.application.PaymentInfo;

public class PaymentV1Dto {
    public record CreateRequest(String orderId, String cardType, String cardNo, Long amount, String callbackUrl) {
        @Override
        public String toString() {
            return "CreateRequest[card input redacted]";
        }
    }

    public record PaymentResponse(String transactionKey, String orderId, long amount, String status) {
        public static PaymentResponse from(PaymentInfo info) {
            return new PaymentResponse(info.transactionKey(), info.orderId(), info.amount(), info.status().name());
        }
    }

    // 거래를 저장하거나 처리를 예약하기 전에 끝나는 거절이다. 이 요청으로는 나중에도 거래가 생기지 않는다.
    public record RejectedResponse(String code, String orderId, long amount, boolean transactionCreated) {
        public static RejectedResponse from(CreateResult.RejectedBeforeCreate rejected) {
            return new RejectedResponse("TEST_PG_REJECTED_BEFORE_CREATE", rejected.orderId(), rejected.amount(), false);
        }
    }
}
