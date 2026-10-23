package com.loopers.application.payment;

import com.loopers.application.product.ProductCacheInvalidator;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;

/**
 * 확정의 TX A — 재고 확보와 결제 시도(발송 시작 기록)를 한 트랜잭션으로 commit한다(docs/week5/design.md 3번).
 * PG 호출은 이 트랜잭션이 끝난 뒤 호출자가 한다. 그래서 Facade와 다른 빈이다(같은 클래스 안 호출은 트랜잭션 프록시를 거치지 않는다).
 *
 * 발송 권한은 여기서 시도를 INSERT해 commit한 실행 하나에만 있다(D-027). 같은 주문의 다른 요청은 주문 락을 기다린 뒤
 * 이미 있는 시도를 보고 PG를 부르지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PaymentAttemptStarter {

    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentGateway paymentGateway;
    private final ProductCacheInvalidator productCacheInvalidator;

    /**
     * 새 시도를 시작했으면 발송에 필요한 값을, 같은 요청의 재시도면(D-015 ②) 빈 값을 돌려준다.
     * 다른 키(③ 409)·다른 주문(④ 400)·DRAFT가 아님(409)·재고 부족은 예외로 전체 rollback된다 — PG 호출 0회.
     */
    @Transactional
    public Optional<StartedPayment> start(Long orderId, Long requesterId, String attemptKey) {
        Order order = orderService.getOwnedOrderForUpdate(orderId, requesterId);
        if (findSameRequest(orderId, attemptKey)) {
            return Optional.empty();
        }
        order.startPgPayment();

        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> productService.decreaseStock(item.getProductId(), item.getQuantity()));
        // 실행은 commit 뒤라, 이후 단계(시도 저장)가 실패해 롤백되면 지우지 않는다(D-004).
        productCacheInvalidator.productsChanged(order.getItems().stream()
            .map(OrderItem::getProductId)
            .toList());

        long amount = order.calculateTotalAmount();
        PaymentAttempt attempt = paymentAttemptRepository.save(
            new PaymentAttempt(orderId, attemptKey, paymentGateway.providerOrderIdOf(orderId), amount));
        orderService.save(order);
        return Optional.of(new StartedPayment(orderId, attempt.getProviderOrderId(), amount));
    }

    /**
     * 시도 INSERT가 UNIQUE에 걸려 롤백된 뒤 다시 읽어 같은 분류를 적용한다(D-026). 같은 요청이면 true,
     * 다른 키·다른 주문이면 예외다. 이미 있는 시도를 찾지 못하면 false — 호출자가 원래 예외를 다시 던진다.
     */
    @Transactional(readOnly = true)
    public boolean isSameRequestAfterConflict(Long orderId, Long requesterId, String attemptKey) {
        orderService.getOwnedOrder(orderId, requesterId);
        return findSameRequest(orderId, attemptKey);
    }

    private boolean findSameRequest(Long orderId, String attemptKey) {
        Optional<PaymentAttempt> ofOrder = paymentAttemptRepository.findByOrderId(orderId);
        if (ofOrder.isPresent()) {
            ofOrder.get().assertSameRequest(orderId, attemptKey);
            return true;
        }
        // 이 주문엔 시도가 없는데 키가 이미 쓰였다면 다른 주문의 키다 — 항상 예외(④).
        paymentAttemptRepository.findByAttemptKey(attemptKey)
            .ifPresent(attempt -> attempt.assertSameRequest(orderId, attemptKey));
        return false;
    }

    public record StartedPayment(Long orderId, String providerOrderId, long amount) {}
}
