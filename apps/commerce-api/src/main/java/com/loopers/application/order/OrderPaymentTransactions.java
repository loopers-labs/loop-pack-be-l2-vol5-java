package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentEventRepository;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PG 결제의 DB 트랜잭션 구간. TX A는 예약, TX B는 결제 결과 저장, TX C는 주문 반영을 담당한다.
 * 잠금 순서는 주문 → 결제 시도 → 상품(ID 오름차순)이다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderPaymentTransactions {

    private final UserService userService;
    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public PaymentStart start(Long userId, Long orderId, String attemptKey, String cardType, String cardNo) {
        // 주문 행을 가장 먼저 잠근다. 이후 일반 조회의 REPEATABLE READ 스냅샷이 앞 요청의 커밋 뒤에 만들어져
        // 이미 저장된 결제 시도를 볼 수 있다(ADR 0010과 같은 이유). 없는 시도를 잠금 조회하면 빈 구간 잠금으로 교착이 난다.
        OrderModel order = orderService.getForUpdate(orderId);
        userService.get(userId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }

        Optional<PaymentAttempt> existing = paymentAttemptRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            if (!existing.get().getAttemptKey().equals(attemptKey)) {
                throw new CoreException(ErrorType.CONFLICT, "이미 결제가 시작된 주문입니다.");
            }
            if (!existing.get().matchesRequest(order.getTotalAmount(), cardType, cardNo)) {
                throw new CoreException(ErrorType.CONFLICT, "같은 결제 요청 키의 요청 내용이 다릅니다.");
            }
            return new PaymentStart(OrderPaymentInfo.from(order, existing.get()), false);
        }
        order.validateConfirmable();
        if (paymentAttemptRepository.findByAttemptKey(attemptKey).isPresent()) {
            throw new CoreException(ErrorType.CONFLICT, "다른 주문에 사용된 결제 요청 키입니다.");
        }

        reserveStock(order);
        order.startPaymentWithReservedStock();
        PaymentAttempt attempt = saveNewAttempt(
            PaymentAttempt.start(orderId, attemptKey, order.getTotalAmount(), cardType, cardNo)
        );
        return new PaymentStart(OrderPaymentInfo.from(order, attempt), true);
    }

    @Transactional
    public OrderPaymentInfo recordCreateResponse(Long orderId, PaymentObservation observation) {
        OrderModel order = orderService.getForUpdate(orderId);
        PaymentAttempt attempt = getAttemptForUpdate(orderId);

        if (observation.kind() == PaymentObservation.Kind.UNKNOWN) {
            PaymentAttempt.Result result = attempt.markUnknown();
            record(attempt, PaymentEvent.Source.CREATE_RESPONSE, null, result, null);
        } else if (observation.kind() == PaymentObservation.Kind.REJECTED) {
            PaymentAttempt.Result result = attempt.reject();
            record(attempt, PaymentEvent.Source.CREATE_RESPONSE, null, result, PaymentEvent.Reason.REJECTED_BEFORE_CREATE);
        } else if (observation.kind() == PaymentObservation.Kind.NOT_SENT) {
            PaymentAttempt.Result result = attempt.markNotSent();
            record(attempt, PaymentEvent.Source.CREATE_RESPONSE, null, result, PaymentEvent.Reason.NOT_SENT);
        } else {
            apply(attempt, observation.transactions().get(0), PaymentEvent.Source.CREATE_RESPONSE);
        }
        return OrderPaymentInfo.from(order, attempt);
    }

    /** TX B: reconcile·callback이 확인한 결제 결과와 이력을 저장한다. 주문·재고는 변경하지 않는다. */
    @Transactional
    public OrderPaymentInfo applyLookup(Long orderId, PaymentObservation observation, PaymentEvent.Source source) {
        OrderModel order = orderService.getForUpdate(orderId);
        PaymentAttempt attempt = getAttemptForUpdate(orderId);
        List<PaymentTransaction> transactions = observation.transactions();

        if (transactions.isEmpty()) {
            record(attempt, source, null, PaymentAttempt.Result.IGNORED, PaymentEvent.Reason.NO_TRANSACTION_YET);
        } else if (transactions.size() > 1) {
            record(attempt, source, null, PaymentAttempt.Result.CONFLICT, PaymentEvent.Reason.MULTIPLE_TRANSACTIONS);
        } else {
            apply(attempt, transactions.get(0), source);
        }
        return OrderPaymentInfo.from(order, attempt);
    }

    /** TX C: 저장된 종결 결과를 주문·예약·재고에 한 번만 반영한다. PG 조회 없이 다시 실행할 수 있다. */
    @Transactional
    public OrderPaymentInfo applyRecordedResult(Long orderId) {
        OrderModel order = orderService.getForUpdate(orderId);
        PaymentAttempt attempt = getAttemptForUpdate(orderId);
        OrderPaymentInfo stored = OrderPaymentInfo.from(order, attempt);
        if (!stored.hasFinalPaymentResult() || stored.isFinished()) {
            return stored;
        }

        if (attempt.getStatus() == PaymentAttemptStatus.SUCCESS) {
            order.completePayment(clock.instant().truncatedTo(ChronoUnit.MICROS));
            events.publishEvent(new OrderConfirmed(order.getId(), order.getConfirmedAt(), order.getItems().stream()
                .map(item -> new OrderConfirmed.Item(item.getProductId(), item.getQuantity())).toList()));
        } else {
            failWithReleasedStock(order);
        }
        return OrderPaymentInfo.from(order, attempt);
    }

    /** callback이 알려 준 PG 주문 번호로 우리 시도를 찾는다. 사용자 확인 없이 PG의 알림에만 쓴다. */
    @Transactional(readOnly = true)
    public Optional<OrderPaymentInfo> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            .map(attempt -> OrderPaymentInfo.from(orderService.get(attempt.getOrderId()), attempt));
    }

    /** 배치처럼 사용자 요청이 아닌 복구에서 주문의 저장 상태를 읽는다. */
    @Transactional(readOnly = true)
    public OrderPaymentInfo get(Long orderId) {
        return stored(orderService.get(orderId));
    }

    @Transactional(readOnly = true)
    public OrderPaymentInfo getMine(Long userId, Long orderId) {
        userService.get(userId);
        OrderModel order = orderService.get(orderId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        return stored(order);
    }

    private OrderPaymentInfo stored(OrderModel order) {
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 시도를 찾을 수 없습니다."));
        return OrderPaymentInfo.from(order, attempt);
    }

    private void apply(PaymentAttempt attempt, PaymentTransaction transaction, PaymentEvent.Source source) {
        if (!transaction.providerOrderId().equals(attempt.getProviderOrderId())) {
            record(attempt, source, transaction, PaymentAttempt.Result.CONFLICT, PaymentEvent.Reason.ORDER_MISMATCH);
            return;
        }
        if (transaction.amount() != attempt.getAmount()) {
            record(attempt, source, transaction, PaymentAttempt.Result.CONFLICT, PaymentEvent.Reason.AMOUNT_MISMATCH);
            return;
        }

        String key = transaction.transactionKey();
        PaymentEvent.Reason conflictReason = attempt.getTransactionKey() != null && !attempt.getTransactionKey().equals(key)
            ? PaymentEvent.Reason.OTHER_TRANSACTION_KEY
            : PaymentEvent.Reason.OPPOSITE_FINAL_RESULT;

        PaymentAttempt.Result result = switch (transaction.status()) {
            case PENDING -> attempt.markPending(key);
            case SUCCESS -> attempt.succeed(key);
            case FAILED -> attempt.fail(key);
        };
        record(attempt, source, transaction, result, result == PaymentAttempt.Result.CONFLICT ? conflictReason : null);
    }

    private void record(PaymentAttempt attempt, PaymentEvent.Source source, PaymentTransaction transaction,
                        PaymentAttempt.Result result, PaymentEvent.Reason reason) {
        if (result == PaymentAttempt.Result.CONFLICT) {
            log.warn("PG 결과 충돌: orderId={}, attemptStatus={}, storedKey={}, observed={}, reason={}",
                attempt.getOrderId(), attempt.getStatus(), attempt.getTransactionKey(), transaction, reason);
        }
        paymentEventRepository.save(PaymentEvent.of(attempt.getId(), source, transaction, result, reason));
    }

    private void reserveStock(OrderModel order) {
        for (OrderItem item : sortedByProductId(order)) {
            ProductModel product = productService.getForUpdate(item.getProductId());
            if (product.getStockQuantity() < item.getQuantity()) {
                throw new CoreException(ErrorType.CONFLICT, "재고가 부족합니다.");
            }
            product.decreaseStock(item.getQuantity());
        }
    }

    private void failWithReleasedStock(OrderModel order) {
        for (OrderItem item : sortedByProductId(order)) {
            productService.getForUpdateIncludingDeleted(item.getProductId()).increaseStock(item.getQuantity());
        }
        order.failPaymentWithReleasedStock();
    }

    private static List<OrderItem> sortedByProductId(OrderModel order) {
        return order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .toList();
    }

    private PaymentAttempt getAttemptForUpdate(Long orderId) {
        return paymentAttemptRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 시도를 찾을 수 없습니다."));
    }

    private PaymentAttempt saveNewAttempt(PaymentAttempt attempt) {
        try {
            return paymentAttemptRepository.save(attempt);
        } catch (DataIntegrityViolationException exception) {
            throw new CoreException(ErrorType.CONFLICT, "이미 결제가 시작된 주문이거나 사용된 결제 요청 키입니다.");
        }
    }

    public record PaymentStart(OrderPaymentInfo payment, boolean shouldSend) {}
}
