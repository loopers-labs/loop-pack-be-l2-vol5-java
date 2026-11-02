package com.loopers.application.payment;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.StockReservation;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.kafka.event.OrderConfirmed;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.Comparator;
import java.util.UUID;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Transactional
public class OrderPaymentTransactions {
    private final IdentifyUser users;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final PaymentRepository payments;
    private final StockReservationRepository reservations;
    private final Clock orderConfirmationClock;
    private final ApplicationEventPublisher events;

    // 주문 잠금을 기다린 실행도 직전 커밋의 결제 행을 읽어야 하므로 새 조회마다 커밋된 상태를 읽는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreparedPayment prepare(Long userId, long orderId, UUID paymentAttemptKey) {
        long owner = users.require(userId);
        Order order = orders.findByIdForUpdate(orderId)
                .filter(found -> found.getUserId() == owner)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        var existingPayment = payments.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            if (!payment.paymentAttemptKey().equals(paymentAttemptKey) || payment.amount() != order.getTotalAmount()) {
                throw new CoreException(ErrorType.CONFLICT);
            }
            return new PreparedPayment(payment, false);
        }
        Payment newPayment = Payment.start(orderId, paymentAttemptKey, order.getTotalAmount());
        if (payments.findByPaymentAttemptKey(paymentAttemptKey).isPresent()) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        order.awaitPayment();
        orders.awaitPaymentIfDraft(order);

        var items = order.getItems().stream()
                .sorted(Comparator.comparingLong(OrderItem::getProductId))
                .toList();
        for (OrderItem item : items) {
            products.deductStock(item.getProductId(), item.getQuantity());
        }
        reservations.holdAll(items.stream()
                .map(item -> new StockReservation(orderId, item.getProductId(), item.getQuantity()))
                .toList());
        return new PreparedPayment(payments.save(newPayment), true);
    }

    public Payment recordAcceptance(long orderId, PaymentGateway.CreatedPayment accepted) {
        Payment payment = findPaymentForUpdate(orderId);
        if (!payment.providerOrderId().equals(accepted.providerOrderId()) || payment.amount() != accepted.amount()) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        return payments.save(payment.accepted(accepted.transactionKey()));
    }

    public Payment recordUnknown(long orderId) {
        return payments.save(findPaymentForUpdate(orderId).unknown());
    }

    @Transactional(readOnly = true)
    public Payment findForReconciliation(Long userId, long orderId) {
        long owner = users.require(userId);
        orders.findById(orderId)
                .filter(order -> order.getUserId() == owner)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        return payments.findByOrderId(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
    }

    public Payment recordLookup(long orderId, PaymentGateway.ObservedPayment observed) {
        Payment payment = findPaymentForUpdate(orderId);
        if (!payment.providerOrderId().equals(observed.providerOrderId()) || payment.amount() != observed.amount()) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        if (observed.status() == PaymentStatus.PENDING) {
            return payments.save(payment.accepted(observed.transactionKey()));
        }
        if (observed.status() == PaymentStatus.FAILED) {
            return applyFailedPayment(payment, observed.transactionKey());
        }
        if (observed.status() != PaymentStatus.SUCCESS) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        Payment completedPayment = payment.succeeded(observed.transactionKey());
        if (payment.status() == PaymentStatus.SUCCESS) {
            return payment;
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.completePayment(orderConfirmationClock.instant().truncatedTo(ChronoUnit.MICROS));
        orders.confirmIfPaymentPending(order);
        // 가용 재고는 TX A에서 이미 차감했다. 성공 시에는 예약만 소비한다.
        reservations.consumeHeldForOrder(orderId, order.getItems().size());
        Payment savedPayment = payments.save(completedPayment);
        events.publishEvent(OrderConfirmed.confirmed(orderId, order.getConfirmedAt(),
                order.getItems().stream()
                        .map(item -> new OrderConfirmed.Item(item.getProductId(), item.getQuantity()))
                        .toList()));
        return savedPayment;
    }

    public Payment recordRejection(long orderId, PaymentGateway.RejectedBeforeCreateException rejection) {
        Payment payment = findPaymentForUpdate(orderId);
        if (!payment.providerOrderId().equals(rejection.providerOrderId()) || payment.amount() != rejection.amount()) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        return applyFailedPayment(payment, payment.rejectedBeforeCreate());
    }

    private Payment applyFailedPayment(Payment payment, String transactionKey) {
        return applyFailedPayment(payment, payment.failed(transactionKey));
    }

    private Payment applyFailedPayment(Payment payment, Payment failedPayment) {
        if (payment.status() == PaymentStatus.FAILED) {
            return payment;
        }
        Order order = orders.findById(payment.orderId())
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.failPayment();
        orders.failIfPaymentPending(order);
        var releasedReservations = reservations.releaseHeldForOrder(order.getId(), order.getItems().size());
        for (var reservation : releasedReservations) {
            products.restoreReservedStock(reservation.productId(), reservation.quantity());
        }
        return payments.save(failedPayment);
    }

    private Payment findPaymentForUpdate(long orderId) {
        // 준비와 결과 반영 모두 주문 → 결제 순서로 잠가 서로 교착하지 않게 한다.
        orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        return payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
    }

    public record PreparedPayment(Payment payment, boolean shouldSend) {}
}
