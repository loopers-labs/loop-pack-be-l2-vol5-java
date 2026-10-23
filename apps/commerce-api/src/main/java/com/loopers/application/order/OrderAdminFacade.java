package com.loopers.application.order;

import com.loopers.application.payment.LookupRetry;
import com.loopers.application.payment.PaymentReconciler;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentConflictRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

// 관리자 전용 — 전체 목록/상세 조회와 결제 확인·실패 처리(소유권 검사 없음). 주문 생성·확정은 고객만 하므로
// OrderFacade에 있다 (docs/week2/design.md 3번 섹션 "Facade 경계" 참고).
@RequiredArgsConstructor
@Component
public class OrderAdminFacade {

    private final OrderService orderService;
    private final OrderInfoAssembler orderInfoAssembler;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentConflictRepository paymentConflictRepository;
    private final PaymentReconciler paymentReconciler;
    private final PaymentResultApplier paymentResultApplier;

    public List<OrderInfo> getOrders() {
        return orderInfoAssembler.withPayments(orderService.getOrders());
    }

    public OrderAdminInfo getOrder(Long orderId) {
        Order order = orderService.getOrder(orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId).orElse(null);
        List<OrderAdminInfo.Conflict> conflicts = attempt == null ? List.of()
            : paymentConflictRepository.findAllByAttemptId(attempt.getId()).stream().map(OrderAdminInfo.Conflict::from).toList();
        return new OrderAdminInfo(OrderInfo.from(order, attempt), conflicts);
    }

    /**
     * 관리자 단건 확인 — 종결된 시도도 PG에 다시 묻는다(D-014). 다른 결과면 상태를 바꾸지 않고 충돌로 기록된다(INV-003).
     * 관리자가 기다리는 경로라 고객 경로와 같은 재시도(3회)를 쓰고, 0건 횟수는 세지 않는다(D-043).
     */
    public OrderAdminInfo reconcilePayment(Long orderId) {
        PaymentAttempt attempt = startedAttempt(orderId);
        paymentReconciler.reconcile(orderId, attempt.getProviderOrderId(), attempt.getTransactionKey(), LookupRetry.CUSTOMER);
        return getOrder(orderId);
    }

    /**
     * 관리자 실패 처리 — PG에 거래가 없음을 사람이 확인한 뒤 부른다(D-009). 거래 키를 모르는 미종결 시도만 허용한다(D-045).
     * 처리자는 인증된 관리자 이름이다(D-046).
     */
    public OrderAdminInfo failPayment(Long orderId, String resolvedBy) {
        startedAttempt(orderId);
        paymentResultApplier.failByAdmin(orderId, resolvedBy, ZonedDateTime.now());
        return getOrder(orderId);
    }

    private PaymentAttempt startedAttempt(Long orderId) {
        orderService.getOrder(orderId);
        return paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "결제를 시작하지 않은 주문입니다."));
    }
}
