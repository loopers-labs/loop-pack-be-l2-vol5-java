package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.StockReservationModel;
import com.loopers.domain.order.StockReservationService;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.PaymentEffect;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.product.ProductRevisionService;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 결제 흐름에서 DB를 바꾸는 짧은 로컬 트랜잭션 둘 (design.md 9-4절). PG 호출은 이 클래스 밖에서, 트랜잭션 없이 한다.
 *
 * <ul>
 *   <li>TX A {@link #start}: 주문·상품을 검사하고 재고를 확보(예약)하며 결제 시도를 저장한다. 어느 하나라도 실패하면 전부 rollback되고 PG는 호출되지 않는다.</li>
 *   <li>TX B {@link #applyCreate}·{@link #applyLookup}: 검증한 PG 관찰을 시도·주문·예약에 한 번만 적용한다.</li>
 * </ul>
 *
 * <p>Facade와 다른 빈이라 {@code @Transactional}이 프록시를 거쳐 적용된다. 같은 객체 안에서 부르면 적용되지 않는다 (design.md 7-4절 반례 1).
 * 잠금 순서는 주문 → 결제 시도 → 재고 예약 → 상품(id 오름차순) → 상품 revision이다 (design.md 9-5절).
 */
@RequiredArgsConstructor
@Component
public class PaymentTransactions {

    private final OrderService orderService;
    private final ProductService productService;
    private final ProductRevisionService productRevisionService;
    private final PaymentAttemptService paymentAttemptService;
    private final StockReservationService stockReservationService;
    private final Clock clock;

    /**
     * TX A의 결과. {@code sendRequired}가 참인 실행만 PG에 거래 생성을 요청한다. 이 트랜잭션을 commit한 실행이 발송 권한을 얻는다.
     * 같은 요청이 이미 시도를 만들어 둔 경우에는 저장된 상태만 돌려주고 발송하지 않는다.
     */
    public record Started(PaymentOutcome outcome, boolean sendRequired, String providerOrderId, long amount) {
    }

    /** 결과를 다시 확인할 시도의 현재 모습. 이미 끝났으면 {@code outcome.settled()}가 참이다. */
    public record Target(PaymentOutcome outcome, Long orderId, String transactionKey, String providerOrderId) {
    }

    @Transactional
    public Started start(Long orderId, Long userId, String attemptKey, String cardType) {
        OrderModel order = orderService.getOrderOwnedByForUpdate(orderId, userId);

        Optional<PaymentAttemptModel> existing = paymentAttemptService.findByOrderId(orderId);
        if (existing.isPresent()) {
            PaymentAttemptModel attempt = existing.get();
            if (!attempt.isSameRequest(attemptKey, cardType)) {
                throw new CoreException(ErrorType.CONFLICT, "이 주문에는 이미 다른 결제 시도가 있습니다.");
            }
            return new Started(outcomeOf(order, attempt), false, attempt.getProviderOrderId(), attempt.getAmount());
        }

        // 결제 시도가 없는 주문은 DRAFT여야 한다. 이전에 포인트로 확정한 주문도 여기서 기존대로 404가 된다
        orderService.getDraftOrderOwnedBy(orderId, userId);
        if (paymentAttemptService.existsByAttemptKey(attemptKey)) {
            throw new CoreException(ErrorType.CONFLICT, "다른 주문에서 이미 사용한 결제 시도 키입니다.");
        }

        List<OrderItem> items = itemsByProductId(order);
        for (OrderItem item : items) {
            productService.decreaseStock(item.getProductId(), item.getQuantity());
        }
        order.startPgPayment();
        PaymentAttemptModel attempt = paymentAttemptService.create(orderId, userId, attemptKey, cardType, order.getTotalAmount());
        stockReservationService.reserve(orderId);
        // 재고가 줄었으므로 상품 캐시의 revision을 올린다. 다른 잠금을 모두 잡은 뒤 마지막에 올린다
        productRevisionService.increaseAll(items.stream().map(OrderItem::getProductId).toList());

        return new Started(outcomeOf(order, attempt), true, attempt.getProviderOrderId(), attempt.getAmount());
    }

    /**
     * 관찰을 적용한 결과. {@code confirmedEvent}는 이번 적용이 주문을 <b>처음</b> CONFIRMED로 만든 경우에만 있다(그 밖에는 null).
     * 이벤트는 트랜잭션 안에서 저장된 값으로 미리 만들어 두므로, commit 뒤에 호출자가 엔티티를 다시 읽지 않아도 된다.
     * 이 값을 받았다면 트랜잭션은 이미 commit됐다.
     */
    public record Applied(PaymentOutcome outcome, OrderConfirmedEvent confirmedEvent) {
    }

    @Transactional
    public Applied applyCreate(Long orderId, PgCreateResult result) {
        return settle(orderId, attempt -> attempt.applyCreate(result));
    }

    @Transactional
    public Applied applyLookup(Long orderId, List<PgTransaction> transactions) {
        return settle(orderId, attempt -> attempt.applyLookup(transactions));
    }

    /** 확인하지 못했다. 상태는 바꾸지 않고, 마지막으로 아는 상태에 확인 불가를 표시해 돌려준다. */
    @Transactional(readOnly = true)
    public PaymentOutcome currentWithLookupUnavailable(Long orderId) {
        return current(orderId).withLookupUnavailable();
    }

    /** 본인 주문의 결제 시도를 읽는다. 남의 주문이거나 시도가 없으면 404다. */
    @Transactional(readOnly = true)
    public Target reconcileTarget(Long orderId, Long userId) {
        OrderModel order = orderService.getOrder(orderId, userId);
        return targetOf(order, paymentAttemptService.getByOrderId(orderId));
    }

    /** callback이 알려 준 PG 주문 번호의 시도를 읽는다. 없으면 404다. 알림 본문의 결과는 쓰지 않는다. */
    @Transactional(readOnly = true)
    public Target callbackTarget(String providerOrderId) {
        PaymentAttemptModel attempt = paymentAttemptService.getByProviderOrderId(providerOrderId);
        return targetOf(orderService.getOrderForAdmin(attempt.getOrderId()), attempt);
    }

    // 주문 → 결제 시도 → 재고 예약 순으로 잠근 뒤, 관찰을 적용하고 시도가 이번에 종결된 경우에만 주문·재고에 효과를 낸다
    private Applied settle(Long orderId, Function<PaymentAttemptModel, PaymentEffect> observation) {
        OrderModel order = orderService.getOrderForUpdate(orderId);
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderIdForUpdate(orderId);
        StockReservationModel reservation = stockReservationService.getByOrderIdForUpdate(orderId);

        OrderConfirmedEvent confirmedEvent = null;
        PaymentEffect effect = observation.apply(attempt);
        switch (effect) {
            case SUCCEEDED -> {
                order.completePgPayment(attempt.getAmount(), clock.instant());
                reservation.consume();
                confirmedEvent = OrderConfirmedEvent.from(order);
            }
            case FAILED -> {
                order.failPgPayment();
                if (reservation.release()) {
                    restock(order);
                }
            }
            case NONE -> {
            }
        }
        return new Applied(outcomeOf(order, attempt), confirmedEvent);
    }

    // 확보했던 수량을 한 번 되돌린다. 품목은 상품 id 오름차순으로 잠그고, revision은 모두 처리한 뒤 올린다
    private void restock(OrderModel order) {
        List<OrderItem> items = itemsByProductId(order);
        for (OrderItem item : items) {
            productService.increaseStock(item.getProductId(), item.getQuantity());
        }
        productRevisionService.increaseAll(items.stream().map(OrderItem::getProductId).toList());
    }

    private PaymentOutcome current(Long orderId) {
        OrderModel order = orderService.getOrderForAdmin(orderId);
        return outcomeOf(order, paymentAttemptService.getByOrderId(orderId));
    }

    private static List<OrderItem> itemsByProductId(OrderModel order) {
        return order.getItems().stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList();
    }

    private static PaymentOutcome outcomeOf(OrderModel order, PaymentAttemptModel attempt) {
        return PaymentOutcome.of(OrderInfo.from(order, attempt));
    }

    private static Target targetOf(OrderModel order, PaymentAttemptModel attempt) {
        return new Target(outcomeOf(order, attempt), order.getId(), attempt.getTransactionKey(), attempt.getProviderOrderId());
    }
}
