package com.loopers.application.order;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;
    private final OrderPaymentProcessor orderPaymentProcessor;
    private final PaymentGateway paymentGateway;
    private final PaymentLookupScheduler paymentLookupScheduler;
    private final PaymentRequestGuard paymentRequestGuard;

    /**
     * FR-ORDER-01 주문 생성. ST-03 (없음) → DRAFT. 재고·잔액 변화 없음, 재고 부족은 확인하지 않는다.
     * 카탈로그 BC 에서 존재·ACTIVE·현재 가격(단가 원천)을 조회한다 (5-6). 검사 순서: 상품 → 품목 규칙(Model).
     */
    @Transactional
    public OrderInfo createOrder(Long requesterId, List<OrderItemCommand> items) {
        userService.getUser(requesterId);
        List<OrderItemCommand> safeItems = items == null ? List.of() : items;
        Map<Long, ProductModel> products = productService
            .getActiveProducts(safeItems.stream().map(OrderItemCommand::productId).toList())
            .stream().collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        List<OrderModel.Line> lines = safeItems.stream()
            .map(item -> new OrderModel.Line(item.productId(), item.quantity(), products.get(item.productId()).getPrice()))
            .toList();
        return OrderInfo.from(orderService.create(requesterId, lines));
    }

    /**
     * FR-ORDER-02 주문 확정 (PG 카드 결제). 트랜잭션 없음: 결제 시작(재고 예약, AWAITING_PAYMENT) 커밋 → PG 요청 → 결과 반영.
     * 결제 요청 전에 백업 거래 조회를 예약한다 (PaymentLookupScheduler, 결제 요청과 서로 기다리지 않는다).
     * 조회 예약·결제 요청은 PaymentRequestGuard(세마포어·서킷) 안에서 한다. 거기서 거절되면 둘 다 하지 않고 NOT_SENT 라 FAILED 다.
     * 같은 결제 시도 키의 재요청은 PG 를 다시 부르지 않고 현재 상태를 돌려준다. 결과를 모르면 AWAITING_PAYMENT 그대로 돌려준다.
     */
    public OrderInfo confirmOrder(Long requesterId, Long orderId, String paymentAttemptKey, String cardType, String cardNo) {
        OrderPaymentProcessor.Started started = orderPaymentProcessor.startPayment(requesterId, orderId, paymentAttemptKey, cardType, cardNo);
        if (started.alreadyStarted()) {
            return started.order();
        }
        OrderInfo order = started.order();
        PaymentGatewayResult result = paymentRequestGuard.run(order.id(), () -> {
            paymentLookupScheduler.schedule(order.userId(), order.id());
            return paymentGateway.request(order.userId(), order.id(), cardType, cardNo, order.totalAmount());
        });
        return orderPaymentProcessor.applyResult(order.id(), result);
    }

    /**
     * 결제 대기 해소. 트랜잭션 없음: 본인 주문 확인 → (AWAITING_PAYMENT 면) PG 에 PG 주문 ID 로 기존 거래 조회 → 결과 반영 (동기 응답·callback 과 같은 applyResult).
     * 새 결제 요청을 만들지 않는다. AWAITING_PAYMENT 가 아니면 PG 를 부르지 않고 현재 상태를 돌려준다. 조회 실패는 UNKNOWN 이라 주문은 그대로다.
     */
    public OrderInfo resolveAwaitingPayment(Long requesterId, Long orderId) {
        OrderInfo order = orderPaymentProcessor.getOwned(requesterId, orderId);
        if (!OrderStatus.AWAITING_PAYMENT.name().equals(order.status())) {
            return order;
        }
        PaymentGatewayResult result = paymentGateway.find(order.userId(), order.id());
        return orderPaymentProcessor.applyResult(order.id(), result);
    }

    /** PG callback 결과 반영. PG 주문 ID("ORDER-{id}")를 우리 주문 ID 로 바꿔 결과 반영에 넘긴다. 형식이 아니면 ER-05 ORDER_NOT_FOUND. */
    public OrderInfo applyPaymentCallback(String pgOrderId, PaymentGatewayResult result) {
        return orderPaymentProcessor.applyResult(PgOrderId.parse(pgOrderId), result);
    }
}
