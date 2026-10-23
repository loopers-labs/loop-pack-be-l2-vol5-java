package com.loopers.payment.application;

import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderConfirmPolicy;
import com.loopers.order.domain.OrderItemModel;
import com.loopers.order.domain.OrderModel;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentOutbox;
import com.loopers.payment.domain.PaymentRequestedEvent;
import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 결제 요청 TX A (plan.md 4장). 주문·재고 선점·결제 시도·발송 장부를 한 트랜잭션으로 저장하고, PG는 부르지 않는다.
 * 잠금 순서: 주문 → 상품 id 오름차순 (W3 ADR-W3-03). 결과 반영(payment → 주문 → 선점 → 상품)과 순환이 없다.
 */
@RequiredArgsConstructor
@Component
public class PaymentRequestService implements PaymentRequestUseCase {

    /** 과제 지정 로컬 테스트 PG (plan.md 7장) */
    static final String PROVIDER = "LOCAL_PG";

    private final OrderPort orderPort;
    private final ProductPort productPort;
    private final OrderConfirmPolicy orderConfirmPolicy;
    private final StockReservationUseCase stockReservationUseCase;
    private final PaymentPort paymentPort;
    private final PaymentOutboxPort paymentOutboxPort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 입력(400) → 주문 잠금·소유(404) → 시도 키 멱등(같은 시도 반환 · 409) → 주문 상태(409) → 상품 재검증·선점(409) 순서로 확인한 뒤 저장한다.
     * 시도 키는 주문을 잠근 뒤에 찾는다 — 같은 키의 동시 요청이 앞 요청의 commit을 보고 같은 시도를 돌려받게 한다.
     */
    @Transactional
    @Override
    public PaymentInfo request(PaymentRequestCommand command) {
        validate(command);
        ZonedDateTime now = ZonedDateTime.now();
        Long orderId = command.orderId();
        OrderModel order = orderPort.findByIdForUpdate(orderId)
            .filter(found -> found.isOwnedBy(command.userId()))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));

        Optional<Payment> sameKey = paymentPort.findByAttemptKey(command.attemptKey());
        if (sameKey.isPresent()) {
            if (!sameKey.get().getOrderId().equals(orderId)) {
                throw new CoreException(ErrorType.CONFLICT, "이 Idempotency-Key는 다른 주문의 결제에 쓰였습니다.");
            }
            return PaymentInfo.of(order, sameKey.get());
        }
        if (paymentPort.findByOrderId(orderId).isPresent()) {
            throw new CoreException(ErrorType.CONFLICT, "[orderId = " + orderId + "] 이미 다른 결제 시도가 있는 주문입니다.");
        }
        order.checkConfirmable();

        List<Long> productIds = order.getItems().stream().map(OrderItemModel::getProductId).toList();
        List<ProductModel> products = productPort.findAllByIdsForUpdate(productIds);
        orderConfirmPolicy.check(order, products);
        Map<Long, Integer> quantities = order.getItems().stream()
            .collect(Collectors.toMap(OrderItemModel::getProductId, OrderItemModel::getQuantity));
        stockReservationUseCase.reserve(orderId, quantities);

        order.startPayment();
        orderPort.save(order);
        Payment payment = savePayment(Payment.request(orderId, command.userId(), command.attemptKey(), PROVIDER, order.paymentAmount(), now));
        paymentOutboxPort.save(PaymentOutbox.paymentRequested(payment.getId()));
        eventPublisher.publishEvent(new PaymentRequestedEvent(payment.getId(), command.cardType(), command.cardNo()));
        return PaymentInfo.of(order, payment);
    }

    private static void validate(PaymentRequestCommand command) {
        if (isBlank(command.attemptKey())) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다.");
        }
        if (isBlank(command.cardType()) || isBlank(command.cardNo())) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류와 카드 번호가 필요합니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 다른 주문을 잠근 두 요청이 같은 키를 동시에 쓰면 attempt_key unique가 막는다 → 409 (plan.md 2).
     */
    private Payment savePayment(Payment payment) {
        try {
            return paymentPort.save(payment);
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.CONFLICT, "같은 Idempotency-Key 또는 같은 주문의 결제 시도가 이미 있습니다.");
        }
    }
}
