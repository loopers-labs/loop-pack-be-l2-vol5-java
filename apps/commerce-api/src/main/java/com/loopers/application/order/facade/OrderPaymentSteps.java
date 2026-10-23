package com.loopers.application.order.facade;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.payment.enums.ReservationChange;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.loopers.application.order.OrderInfo;

/** PG 결제의 로컬 트랜잭션 단계. 자기 호출로는 트랜잭션이 열리지 않아 Facade 와 bean 을 나눔. 잠금 순서는 orders → product (D-63) */
@RequiredArgsConstructor
@Component
public class OrderPaymentSteps {
    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentService paymentService;

    /** sendable: 이번 실행이 발송 권한을 얻음 */
    public record Started(
        OrderInfo order,
        boolean sendable,
        String providerOrderId,
        long amount,
        Set<Long> soldOutBrandIds,
        Set<Long> soldOutProductIds
    ) {}

    /** restocked: 반환으로 품절이 풀린 상품 */
    public record Applied(OrderInfo order, Set<Long> restockedBrandIds, Set<Long> restockedProductIds) {}

    public record Snapshot(OrderInfo order, String providerOrderId, boolean settled) {}

    /** TX A. 검사에 실패하면 전체 rollback 되어 PG 를 부르지 않음 */
    @Transactional
    public Started start(Long userId, Long orderId, String attemptKey) {
        Order order = orderService.getMyOrderForUpdate(userId, orderId);
        Optional<Payment> existing = paymentService.findByOrderId(order.getId());
        if (existing.isPresent()) {
            if (!existing.get().hasAttemptKey(attemptKey)) {
                throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
            }
            return new Started(OrderInfo.from(order, existing.get()), false, existing.get().getProviderOrderId(),
                existing.get().getAmount(), Set.of(), Set.of());
        }
        // 바꾸는 행동보다 검사를 먼저 둠 (만료 → 삭제 → 재고, D-16)
        orderService.validateConfirmable(order);
        paymentService.validateAttemptKeyAvailable(attemptKey);
        Map<Long, Product> products = productService.getActiveProductsForUpdate(productIdsOf(order));
        // 캐시는 품절 여부만 담으므로 이번 확보로 품절된 상품만 무효화함
        Set<Long> soldOutBrandIds = new LinkedHashSet<>();
        Set<Long> soldOutProductIds = new LinkedHashSet<>();
        for (OrderItem item : order.getItems()) {
            Product product = products.get(item.getProductId());
            boolean wasSoldOut = product.isSoldOut();
            product.decrease(item.getQuantity());
            if (!wasSoldOut && product.isSoldOut()) {
                soldOutBrandIds.add(product.getBrandId());
                soldOutProductIds.add(product.getId());
            }
        }
        // 0원 주문은 PG 를 부르지 않고 여기서 확정함 (D-68)
        if (order.getTotalAmount() == 0) {
            Payment payment = paymentService.recordNotRequired(order.getId(), attemptKey);
            orderService.confirmWithoutPayment(order);
            return new Started(OrderInfo.from(order, payment), false, payment.getProviderOrderId(), 0L,
                soldOutBrandIds, soldOutProductIds);
        }
        Payment payment = paymentService.request(order.getId(), attemptKey, order.getTotalAmount());
        orderService.startPayment(order);
        return new Started(OrderInfo.from(order, payment), true, payment.getProviderOrderId(), payment.getAmount(),
            soldOutBrandIds, soldOutProductIds);
    }

    /** TX B: 생성 응답 적용 */
    @Transactional
    public Applied applyCreateResult(Long orderId, PaymentCreateResult result) {
        Order order = orderService.getOrderForUpdate(orderId);
        Payment payment = paymentService.getByOrderId(orderId);
        ReservationChange change = paymentService.apply(payment, result);
        return settle(order, payment, change);
    }

    /** 결제 시도가 없으면(DRAFT · 이전 POINT 주문) PAYMENT_NOT_FOUND */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Long userId, Long orderId) {
        Order order = orderService.getMyOrder(userId, orderId);
        Payment payment = paymentService.getByOrderId(order.getId());
        return new Snapshot(OrderInfo.from(order, payment), payment.getProviderOrderId(), payment.getStatus().isTerminal());
    }

    /** 콜백용. PG 가 보낸 주문 식별자로 찾으므로 본인 확인 없음 */
    @Transactional(readOnly = true)
    public Snapshot snapshotByProviderOrderId(String providerOrderId) {
        Payment payment = paymentService.getByProviderOrderId(providerOrderId);
        Order order = orderService.getOrder(payment.getOrderId());
        return new Snapshot(OrderInfo.from(order, payment), payment.getProviderOrderId(), payment.getStatus().isTerminal());
    }

    /** TX B: 조회 결과 적용. 판정은 Payment 가 함 */
    @Transactional
    public Applied applyLookupResult(Long orderId, PaymentLookupResult result) {
        Order order = orderService.getOrderForUpdate(orderId);
        Payment payment = paymentService.getByOrderId(orderId);
        ReservationChange change = paymentService.apply(payment, result);
        return settle(order, payment, change);
    }

    /** 결제 종결과 같은 TX 에서 후속 효과를 한 번만 일으킴 (D-59) */
    private Applied settle(Order order, Payment payment, ReservationChange change) {
        Set<Long> restockedBrandIds = new LinkedHashSet<>();
        Set<Long> restockedProductIds = new LinkedHashSet<>();
        switch (change) {
            case CONSUME -> orderService.confirmPaid(order, payment.getAmount());
            case RELEASE -> {
                Map<Long, Product> products = productService.getProductsForUpdate(productIdsOf(order));
                for (OrderItem item : order.getItems()) {
                    Product product = products.get(item.getProductId());
                    boolean wasSoldOut = product.isSoldOut();
                    product.release(item.getQuantity());
                    if (wasSoldOut && !product.isSoldOut()) {
                        restockedBrandIds.add(product.getBrandId());
                        restockedProductIds.add(product.getId());
                    }
                }
                orderService.failPayment(order);
            }
            case NONE -> { }
        }
        return new Applied(OrderInfo.from(order, payment), restockedBrandIds, restockedProductIds);
    }

    private static List<Long> productIdsOf(Order order) {
        return order.getItems().stream().map(OrderItem::getProductId).toList();
    }
}
