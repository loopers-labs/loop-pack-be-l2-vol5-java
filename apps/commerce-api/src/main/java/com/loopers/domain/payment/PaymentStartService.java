package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;

/**
 * 로컬 TX A. 하나의 트랜잭션 안에서 아래 순서로 진행하고, 어느 단계든 거절되면 PG 호출 없이 전체 rollback 한다.
 * ① 주문 행 잠금(SELECT … FOR UPDATE) 후 소유권 → 기존 시도(키·요청 일치) → DRAFT 순으로 판정
 * ② 주문 DRAFT → PAYMENT_PENDING, 결제수단 PG
 * ③ 품목을 productId 오름차순으로 재고 차감(WHERE stock >= n) + 예약 RESERVED 저장
 * ④ 결제 시도 REQUESTED 저장 (UNIQUE order_id, UNIQUE user_id+attempt_key)
 * commit 이 발송 시작 기록이다 (설계 1-2, 3장).
 */
@RequiredArgsConstructor
@Component
public class PaymentStartService {

    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ProductService productService;
    private final StockReservationRepository stockReservationRepository;

    @Transactional
    public PaymentStart start(Long userId, Long orderId, PaymentStartCommand command) {
        Order order = orderRepository.findByIdForUpdate(orderId)
            .filter(found -> found.isOwnedBy(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
        long amount = order.getTotalAmount();

        Optional<PaymentAttempt> existing = paymentAttemptRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), amount, command);
        }
        if (paymentAttemptRepository.findByUserIdAndAttemptKey(userId, command.attemptKey()).isPresent()) {
            throw new CoreException(ErrorType.CONFLICT, "이미 다른 주문에 사용된 결제 시도 키입니다.");
        }
        if (order.getStatus() == Order.OrderStatus.CONFIRMED) {
            throw new CoreException(ErrorType.CONFLICT, "이미 확정된 주문입니다.");
        }

        order.startPayment();
        reserveStock(orderId, order);

        PaymentAttempt attempt = paymentAttemptRepository.save(new PaymentAttempt(
            orderId, userId, command.attemptKey(), PaymentAttempt.providerOrderIdOf(orderId), amount, command.cardType()));
        return PaymentStart.started(attempt);
    }

    /**
     * 같은 키 + 같은 요청(금액·cardType)이면 저장된 시도를 그대로 돌려준다. 그 외는 충돌이다 (설계 4-1).
     */
    private PaymentStart resolveExisting(PaymentAttempt attempt, long amount, PaymentStartCommand command) {
        if (!attempt.hasAttemptKey(command.attemptKey())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 결제 시도가 있는 주문입니다.");
        }
        if (!attempt.isSameRequest(amount, command.cardType())) {
            throw new CoreException(ErrorType.CONFLICT, "같은 결제 시도 키의 요청 내용이 다릅니다.");
        }
        return PaymentStart.existing(attempt);
    }

    /**
     * productId 오름차순으로 차감해 교차 데드락을 피한다. 없거나 삭제된 상품은 404, 재고 부족은 409 로 전체가 rollback 된다.
     */
    private void reserveStock(Long orderId, Order order) {
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> {
                productService.deductStock(item.getProductId(), item.getQuantity());
                stockReservationRepository.save(new StockReservation(orderId, item.getProductId(), item.getQuantity()));
            });
    }
}