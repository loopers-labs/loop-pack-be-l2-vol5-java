package com.loopers.payment.application.port.in;

/**
 * 결과를 모르는 결제를 지금 확인하는 입구 (plan.md 8). PG 권위 조회 → 결과 적용 경계. 결제를 생성하지 않는다.
 */
public interface PaymentReconcileUseCase {

    /**
     * 본인 주문의 결제를 지금 조회해 반영하고 현재 상태를 돌려준다. 없거나 남의 주문·결제 시도가 없으면 404.
     */
    PaymentInfo reconcile(Long userId, Long orderId);

    /**
     * PG callback은 계기일 뿐이다 — 본문의 상태를 믿지 않고 권위 조회로 반영한다. 모르는 PG 주문 식별자면 404.
     */
    void reconcileByCallback(String providerOrderId);
}
