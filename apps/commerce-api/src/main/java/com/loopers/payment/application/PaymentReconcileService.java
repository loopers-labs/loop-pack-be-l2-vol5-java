package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 고객 요청·PG callback으로 결제를 지금 확인한다 (W5 plan.md 8). 판단과 반영은 결과 적용 경계(PaymentResultApplier)가 한다.
 * 트랜잭션을 열지 않는다 — PG 조회는 잠금 밖이고, 반영은 적용 경계가 자기 트랜잭션으로 한다.
 */
@RequiredArgsConstructor
@Component
public class PaymentReconcileService implements PaymentReconcileUseCase {

    private final PaymentQueryService paymentQueryService;
    private final PaymentPort paymentPort;
    private final PaymentResultApplier paymentResultApplier;

    @Override
    public PaymentInfo reconcile(Long userId, Long orderId) {
        PaymentInfo current = paymentQueryService.getMyPayment(userId, orderId);
        paymentResultApplier.reconcile(current.paymentId());
        return paymentQueryService.getMyPayment(userId, orderId);
    }

    @Override
    public void reconcileByCallback(String providerOrderId) {
        Payment payment = paymentPort.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + providerOrderId + "] 결제를 찾을 수 없습니다."));
        paymentResultApplier.reconcile(payment.getId());
    }
}
