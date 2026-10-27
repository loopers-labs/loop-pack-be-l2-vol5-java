package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.interfaces.api.order.OrderV1Dto;

public class PaymentV1Dto {

    /**
     * PG 결과 알림 본문 (과제 5장: providerOrderId, transactionKey, status). status 는 신뢰하지 않고 권위 조회로 판정한다.
     */
    public record CallbackRequest(String providerOrderId, String transactionKey, String status) {}

    /**
     * callback 응답. 상대가 PG 라 내부 orderId·transactionKey 는 담지 않는다. 200(종결)·202(진행 중) 공통.
     */
    public record CallbackResponse(String providerOrderId, String orderStatus, String attemptStatus) {
        public static CallbackResponse from(String providerOrderId, OrderConfirmInfo info) {
            return new CallbackResponse(providerOrderId, info.order().status(), info.attemptStatus());
        }
    }

    /**
     * reconcile 응답. confirm 응답(OrderV1Dto.ConfirmResponse)과 같은 모양에 "확인 불가" 한 칸을 더했다 (설계 5-6 결정 ⑳).
     * lookupUnavailable 은 PG 조회 실패·충돌에서 true 다. transactionKey 와 카드 번호는 담지 않는다.
     */
    public record ReconcileResponse(
        Long orderId,
        String orderStatus,
        String attemptStatus,
        boolean lookupUnavailable,
        OrderV1Dto.ConfirmResponse.Links links
    ) {
        public static ReconcileResponse from(OrderConfirmInfo info) {
            Long orderId = info.order().orderId();
            return new ReconcileResponse(
                orderId,
                info.order().status(),
                info.attemptStatus(),
                info.lookupUnavailable(),
                new OrderV1Dto.ConfirmResponse.Links("/api/v1/orders/" + orderId, "/api/v1/orders/" + orderId + "/payment/reconcile")
            );
        }
    }
}
