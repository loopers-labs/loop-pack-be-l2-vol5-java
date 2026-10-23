package com.loopers.application.order;

import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.stream.Collectors;

/**
 * FR-ORDER-02 의 트랜잭션 단위. 결제 시작·결과 반영·결제 대기 만료 (+ 결제 대기 해소 전 본인 주문 확인). PG 호출은 이들 사이, 트랜잭션 밖에서 Facade 가 한다.
 * 락 순서는 둘 다 주문 → 재고(product_id 오름차순) → 상품 (DR-34, DR-38).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderPaymentProcessor {
    private final UserService userService;
    private final OrderService orderService;
    private final ProductService productService;

    /** startPayment 결과. alreadyStarted 면 같은 결제 시도 키의 재요청이라 Facade 는 PG 를 부르지 않는다. */
    public record Started(OrderInfo order, boolean alreadyStarted) {
    }

    /**
     * ST-03 DRAFT → AWAITING_PAYMENT. 재고 예약까지 한 트랜잭션, 커밋 후 Facade 가 PG 를 부른다.
     * 검사 순서: 사용자 → 주문 없음 → 소유 → (같은 키면 현재 상태 반환) → 키 형식 → DRAFT → 상품 삭제 → 재고.
     */
    @Transactional
    public Started startPayment(Long requesterId, Long orderId, String paymentAttemptKey, String cardType, String cardNo) {
        userService.getUser(requesterId);
        OrderModel order = orderService.getOwnedForUpdate(orderId, requesterId);
        if (order.isSamePaymentAttempt(paymentAttemptKey)) {
            return new Started(OrderInfo.from(order), true);
        }
        order.startPayment(paymentAttemptKey, cardType, cardNo);
        productService.getActiveProductsForReserve(order.getItems().stream().map(OrderItemModel::getProductId).toList());
        for (OrderItemModel item : order.getItems()) {
            productService.reserveStock(order.getId(), item.getProductId(), item.getQuantity());
        }
        return new Started(OrderInfo.from(order), false);
    }

    /** 결제 대기 해소의 사전 확인. 본인 주문을 락 없이 읽어 돌려준다. 검사 순서: 사용자 → 주문 없음 → 소유. */
    @Transactional(readOnly = true)
    public OrderInfo getOwned(Long requesterId, Long orderId) {
        userService.getUser(requesterId);
        return OrderInfo.from(orderService.getOwned(orderId, requesterId));
    }

    /**
     * 결제 결과 반영. 동기 응답·callback·결제 대기 해소 공용. 주문 행 락으로 직렬화하고, AWAITING_PAYMENT 가 아니면 아무것도 하지 않는다 (멱등).
     * SUCCESS → CONFIRMED (금액이 합계와 다르면 반영하지 않는다) / FAILED·NOT_SENT·ERROR → FAILED + 재고 반환 / PENDING·UNKNOWN → 거래 키만 기록.
     */
    @Transactional
    public OrderInfo applyResult(Long orderId, PaymentGatewayResult result) {
        OrderModel order = orderService.getForUpdate(orderId);
        // ERROR 로 FAILED 가 된 뒤 늦게 온 SUCCESS 도 여기서 무시된다. 결제 취소가 도입되면 이 경우를 결제 취소로 보낸다.
        if (!order.isAwaitingPayment()) {
            log.info("이미 결론난 주문, 결과 무시 [orderId = {}, status = {}, outcome = {}]", orderId, order.getStatus(), result.outcome());
            return OrderInfo.from(order);
        }
        if (order.getTransactionKey() != null && result.transactionKey() != null && !order.getTransactionKey().equals(result.transactionKey())) {
            log.warn("PG 거래 키 불일치, 결과 무시 [orderId = {}, saved = {}, received = {}]", orderId, order.getTransactionKey(), result.transactionKey());
            return OrderInfo.from(order);
        }
        if (result.transactionKey() != null) {
            order.recordTransactionKey(result.transactionKey());
        }
        switch (result.outcome()) {
            case SUCCESS -> {
                if (!order.getTotalAmount().equals(result.amount())) {
                    log.warn("PG 결제 금액 불일치, 확정 보류 [orderId = {}, total = {}, paid = {}]", orderId, order.getTotalAmount(), result.amount());
                    return OrderInfo.from(order);
                }
                order.confirm();
            }
            case FAILED, NOT_SENT, ERROR -> failAndReleaseStocks(order);
            case PENDING, UNKNOWN -> {
            }
        }
        return OrderInfo.from(order);
    }

    /**
     * 결제 대기 만료. startedBefore 이전에 결제를 시작하고 아직 AWAITING_PAYMENT 인 주문 하나를 FAILED + 재고 반환한다. 처리했으면 true.
     * 주문 하나에 트랜잭션 하나라 락 순서가 applyResult 와 같다 (주문 → 재고). 다른 트랜잭션이 잠근 주문(결과 반영 중)은 건너뛴다 (SKIP LOCKED).
     * 늦게 온 SUCCESS 는 applyResult 가 이미 결론난 주문이라 무시한다.
     */
    @Transactional
    public boolean expireOne(ZonedDateTime startedBefore) {
        return orderService.findExpiredAwaitingForUpdate(startedBefore)
            .map(order -> {
                log.warn("결제 대기 만료, 실패 처리 [orderId = {}, paymentStartedAt = {}]", order.getId(), order.getPaymentStartedAt());
                failAndReleaseStocks(order);
                return true;
            })
            .orElse(false);
    }

    private void failAndReleaseStocks(OrderModel order) {
        order.fail();
        productService.releaseStocks(order.getId(), order.getItems().stream()
            .collect(Collectors.toMap(OrderItemModel::getProductId, OrderItemModel::getQuantity)));
    }
}
