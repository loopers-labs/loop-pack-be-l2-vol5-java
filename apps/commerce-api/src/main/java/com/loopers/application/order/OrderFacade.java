package com.loopers.application.order;

import com.loopers.application.payment.CreatePaymentRequest;
import com.loopers.application.payment.CreateResult;
import com.loopers.application.payment.LookupRetry;
import com.loopers.application.payment.PaymentAttemptStarter;
import com.loopers.application.payment.PaymentAttemptStarter.StartedPayment;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentReconciler;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 고객 전용 — 생성·확정·내 목록/상세. 관리자 조회(전체 목록/상세)는 OrderAdminFacade로 분리한다.
// Order는 Brand/Product와 달리 고객도 변경(생성·확정)을 하므로 "조회=공유, 변경=분리" 기준을
// 그대로 재사용할 수 없다 (docs/week2/design.md 1번 섹션·3번 섹션 "Facade 경계" 참고).
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentAttemptStarter paymentAttemptStarter;
    private final PaymentResultApplier paymentResultApplier;
    private final PaymentReconciler paymentReconciler;
    private final OrderInfoAssembler orderInfoAssembler;
    private final PaymentGateway paymentGateway;
    private final PaymentAttemptRepository paymentAttemptRepository;

    /**
     * 요청 품목마다 단가를 스냅샷으로 찍어(DRAFT 생성 시점, 3번 섹션) Order에 넘긴다.
     * 품목 병합·빈 목록 거절은 Order 생성자의 책임이라 여기서 다시 검사하지 않는다.
     * 재고·가격을 바꾸지 않으므로 상품 캐시를 무효화하지 않는다 (docs/week4/design.md 7번 섹션).
     */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderItemRequest> requestItems) {
        List<OrderItemRequest> items = requestItems == null ? List.of() : requestItems;

        List<Long> distinctProductIds = items.stream()
            .map(OrderItemRequest::productId)
            .distinct()
            .toList();
        Map<Long, ProductModel> productsById = productService.getProductsByIds(distinctProductIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, product -> product));

        List<Order.OrderItemDraft> drafts = items.stream()
            .map(item -> toDraft(item, productsById))
            .toList();

        Order order = orderService.createOrder(userId, drafts);
        return OrderInfo.from(order);
    }

    private Order.OrderItemDraft toDraft(OrderItemRequest item, Map<Long, ProductModel> productsById) {
        ProductModel product = productsById.get(item.productId());
        if (product == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + item.productId() + "] 상품을 찾을 수 없습니다.");
        }
        return new Order.OrderItemDraft(item.productId(), item.quantity(), product.getPrice());
    }

    /**
     * 확정 = PG 결제 시작(docs/week5/design.md 3번). 이 메서드에는 트랜잭션이 없다 — PG를 기다리는 동안 DB 트랜잭션과
     * 재고 락을 쥐지 않기 위해서다. 순서만 조합한다:
     * TX A(재고 확보·시도 기록 commit) → 트랜잭션 밖 create → 결과 기록(짧은 TX) → 응답을 잃었으면 바로 재조회(ⓐ) → TX B.
     *
     * - create는 TX A가 commit된 뒤 한 번만 보낸다. 자동 재시도하지 않는다(INV-005).
     * - 같은 요청의 재시도(D-015 ②)는 PG를 부르지 않고 저장된 상태를 돌려준다.
     * - 시도 INSERT가 UNIQUE에 걸리면 다시 읽어 같은 분류를 적용한다(D-026).
     */
    public OrderInfo confirmOrder(Long orderId, Long requesterId, ConfirmPaymentCommand command) {
        Optional<StartedPayment> started;
        try {
            started = paymentAttemptStarter.start(orderId, requesterId, command.attemptKey());
        } catch (DataIntegrityViolationException e) {
            if (!paymentAttemptStarter.isSameRequestAfterConflict(orderId, requesterId, command.attemptKey())) {
                throw e;
            }
            started = Optional.empty();
        }
        started.ifPresent(payment -> send(payment, command));
        return getMyOrder(orderId, requesterId);
    }

    private void send(StartedPayment payment, ConfirmPaymentCommand command) {
        CreateResult result = paymentGateway.create(new CreatePaymentRequest(
            payment.providerOrderId(), command.cardType(), command.cardNo(), payment.amount()));
        switch (result) {
            case CreateResult.Accepted accepted -> paymentResultApplier.recordAccepted(payment.orderId(), accepted.transactionKey());
            case CreateResult.RejectedNoEffect rejected -> paymentResultApplier.rejectBeforeCreate(payment.orderId());
            case CreateResult.Unknown unknown -> {
                paymentResultApplier.recordUnknown(payment.orderId());
                // 응답만 잃은 경우(ⓐ)를 고객이 기다리는 동안 짧게 확인한다(D-028). 키를 모르므로 주문 식별자로 목록 조회한다.
                paymentReconciler.reconcile(payment.orderId(), payment.providerOrderId(), null, LookupRetry.IMMEDIATE);
            }
        }
    }

    /**
     * 고객 결제 확인 — 이미 시작한 결제의 기존 거래를 PG에 물어 반영한다. 새 결제를 만들지 않는다(pg-contract.md 6번).
     * - 결제 시도가 없는 주문(DRAFT, 이전 POINT 결제)은 409다(D-039). 남의 주문은 404다.
     * - 이미 종결됐으면 PG를 부르지 않고 저장된 상태를 돌려준다(D-037).
     * - 조회가 실패해도 상태를 바꾸지 않고 저장된 상태를 돌려준다(D-038, INV-004).
     */
    public OrderInfo reconcilePayment(Long orderId, Long requesterId) {
        orderService.getOwnedOrder(orderId, requesterId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "결제를 시작하지 않은 주문입니다."));
        if (!attempt.getStatus().isTerminal()) {
            paymentReconciler.reconcile(orderId, attempt.getProviderOrderId(), attempt.getTransactionKey(), LookupRetry.CUSTOMER);
        }
        return getMyOrder(orderId, requesterId);
    }

    public List<OrderInfo> getMyOrders(Long requesterId) {
        return orderInfoAssembler.withPayments(orderService.getMyOrders(requesterId));
    }

    public OrderInfo getMyOrder(Long orderId, Long requesterId) {
        Order order = orderService.getOwnedOrder(orderId, requesterId);
        return OrderInfo.from(order, paymentAttemptRepository.findByOrderId(orderId).orElse(null));
    }
}
