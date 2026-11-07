package com.loopers.support.w6;

import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderConfirmedOutboxFactory;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderPaymentProcessor;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.stream.Collectors;

/**
 * W6 의 결과 반영 (revision 6f7aac1 의 OrderPaymentProcessor.applyResult 그대로). 최초 확정에서 OrderConfirmedEvent 를 등록할 뿐 Outbox 를 남기지 않는다.
 * 결제 시작·만료는 W7 과 같아 부모 것을 쓴다.
 */
public class W6OrderPaymentProcessor extends OrderPaymentProcessor {
    private static final Logger log = LoggerFactory.getLogger(W6OrderPaymentProcessor.class);

    private final OrderService orderService;
    private final ProductService productService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    W6OrderPaymentProcessor(UserService userService, OrderService orderService, ProductService productService,
                            OrderConfirmedOutboxFactory outboxFactory, ApplicationEventPublisher eventPublisher, Clock clock) {
        super(userService, orderService, productService, outboxFactory, clock);
        this.orderService = orderService;
        this.productService = productService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderInfo applyResult(Long orderId, PaymentGatewayResult result) {
        OrderModel order = orderService.getForUpdate(orderId);
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
                order.confirm(ZonedDateTime.now(clock));
                eventPublisher.publishEvent(OrderConfirmedEvent.from(order));
            }
            case FAILED, NOT_SENT, ERROR -> {
                order.fail();
                productService.releaseStocks(order.getId(), order.getItems().stream()
                    .collect(Collectors.toMap(OrderItemModel::getProductId, OrderItemModel::getQuantity)));
            }
            case PENDING, UNKNOWN -> {
            }
        }
        return OrderInfo.from(order);
    }
}
