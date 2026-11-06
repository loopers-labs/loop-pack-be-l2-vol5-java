package com.loopers.crossapp;

import com.loopers.application.payment.OrderConfirmedPublishException;
import com.loopers.application.payment.OrderConfirmedPublisher;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * W6 "after-commit 직접 발행" 확정 경로의 테스트 전용 보존본 (7주차 과제 📄 p.4 5-1 "보존한 W6 테스트 구성").
 * W7 이 제품 코드의 직접 발행을 Outbox + relay 로 바꾼 뒤에도, 이 경로로 "확정 TX 는 commit 됐지만 Outbox 도 발행도 없는" W6 상태를
 * 새 격리 DB 에 만든다. 제품 코드에는 아무 스위치를 두지 않고(📄 p.4 5-4), SUCCESS 를 DB 에 직접 insert 하지 않는다.
 * 실제 리포지터리·실제 PG 조회(FakePaymentGateway 는 PG 응답만 흉내)·실제 발행기를 거친다.
 *
 * <p>복제 출처: 태그 {@code w6-before-outbox} (커밋 17d7c44)
 * <ul>
 *   <li>TX 밖 PG 조회·대조: {@code apps/commerce-api/src/main/java/com/loopers/application/payment/PaymentFacade.java}
 *       54~72행 applyCallback, 192~215행 conflictOf, 227~240행 apply</li>
 *   <li>확정 TX: {@code apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentResultService.java} 40~76행 applyFinal 의
 *       SUCCESS 분기 (시도 종결 → 주문 CONFIRMED → 품목으로 OrderConfirmedEvent 생성 → 예약 CONSUMED). Outbox 는 없다</li>
 *   <li>commit 뒤 발행: 같은 PaymentFacade.java 247~258행 publishAfterCommit</li>
 * </ul>
 * 원본과 다른 점: (1) {@code @Transactional} 대신 {@link TransactionTemplate} 로 같은 REQUIRED 트랜잭션을 연다 (한 클래스 안에서 TX 경계를
 * 두기 위해서다). (2) 이 보존본은 검증된 SUCCESS 재현만 맡으므로 FAILED·PENDING 분기와 충돌 기록(recordConflict)은 복제하지 않고,
 * 조회 결과가 네 항목 대조를 통과한 SUCCESS 1건이 아니면 추측 없이 예외로 드러낸다.
 */
public class W6DirectPublishPath {

    private static final Logger log = LoggerFactory.getLogger(W6DirectPublishPath.class);

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderRepository orderRepository;
    private final StockReservationRepository stockReservationRepository;
    private final PaymentGateway paymentGateway;
    private final OrderConfirmedPublisher orderConfirmedPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public W6DirectPublishPath(
        PaymentAttemptRepository paymentAttemptRepository,
        OrderRepository orderRepository,
        StockReservationRepository stockReservationRepository,
        PaymentGateway paymentGateway,
        OrderConfirmedPublisher orderConfirmedPublisher,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.orderRepository = orderRepository;
        this.stockReservationRepository = stockReservationRepository;
        this.paymentGateway = paymentGateway;
        this.orderConfirmedPublisher = orderConfirmedPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * W6 callback 입구와 같은 순서: (TX 밖) 시도 조회 → PG 목록 조회 → 네 항목 대조 → (TX) 확정 → (commit 뒤) 발행.
     *
     * @return 이 호출이 주문을 처음 CONFIRMED 로 만들며 발행을 시도한 이벤트. 발행기가 중단·실패해도 이벤트는 돌려준다 (주문은 CONFIRMED 로 남는다)
     */
    public OrderConfirmedEvent confirmByCallback(String providerOrderId, String transactionKey) {
        // --- TX 밖 (PaymentFacade.applyCallback 54~70행) ---
        PaymentAttempt attempt = paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new IllegalStateException("[providerOrderId = " + providerOrderId + "] 결제 시도를 찾을 수 없습니다."));
        if (attempt.getStatus().isTerminal()) {
            throw new IllegalStateException("[providerOrderId = " + providerOrderId + "] 이미 종결된 시도입니다: " + attempt.getStatus());
        }
        PaymentLookupResult lookup = paymentGateway.findAllByOrderId(providerOrderId);
        if (lookup.isUnavailable()) {
            throw new IllegalStateException("PG 조회에 실패해 결제 결과를 확인하지 못했습니다.");
        }
        PaymentTransaction transaction = verifiedSuccess(attempt, lookup.transactions(), transactionKey);

        // --- 확정 TX (PaymentResultService.applyFinal 40~76행 SUCCESS 분기). 리턴 = commit ---
        OrderConfirmedEvent firstConfirmation = transactionTemplate.execute(status ->
            applyVerifiedSuccess(attempt.getId(), PaymentFinalResult.success(transaction.transactionKey())));
        if (firstConfirmation == null) {
            throw new IllegalStateException("[orderId = " + attempt.getOrderId() + "] 주문이 처음 CONFIRMED 로 바뀐 경우가 아닙니다.");
        }

        // --- commit 뒤 발행 (PaymentFacade.publishAfterCommit 247~258행) ---
        publishAfterCommit(firstConfirmation);
        return firstConfirmation;
    }

    /** PaymentFacade.conflictOf 192~215행의 네 항목 대조 + 이 보존본이 다루는 SUCCESS 1건인지 확인. 아니면 예외. */
    private PaymentTransaction verifiedSuccess(PaymentAttempt attempt, List<PaymentTransaction> transactions, String notifiedTransactionKey) {
        if (transactions.size() != 1) {
            throw new IllegalStateException("[providerOrderId = " + attempt.getProviderOrderId() + "] PG 거래가 1건이 아닙니다: " + transactions.size());
        }
        PaymentTransaction transaction = transactions.get(0);
        if (!transaction.providerOrderId().equals(attempt.getProviderOrderId())) {
            throw new IllegalStateException("PG 거래의 주문 식별자가 시도와 다릅니다 (ORDER_MISMATCH).");
        }
        if (attempt.getTransactionKey() != null && !attempt.getTransactionKey().equals(transaction.transactionKey())) {
            throw new IllegalStateException("PG 거래 key 가 시도에 저장된 key 와 다릅니다 (TRANSACTION_KEY_MISSING).");
        }
        if (notifiedTransactionKey != null && !notifiedTransactionKey.equals(transaction.transactionKey())) {
            throw new IllegalStateException("PG 거래 key 가 callback 으로 알린 key 와 다릅니다 (TRANSACTION_KEY_MISSING).");
        }
        if (transaction.amount() != attempt.getAmount()) {
            throw new IllegalStateException("PG 거래 금액이 시도 금액과 다릅니다 (AMOUNT_MISMATCH).");
        }
        if (transaction.status() != PaymentTransaction.Status.SUCCESS) {
            throw new IllegalStateException("이 보존본은 검증된 SUCCESS 만 다룹니다. 조회된 상태: " + transaction.status());
        }
        return transaction;
    }

    /**
     * PaymentResultService.applyFinal 40~76행의 SUCCESS 분기를 그대로 옮겼다. 호출자가 TX 를 연다.
     *
     * @return 이 호출이 주문을 처음 CONFIRMED 로 만들었으면 발행 payload, 아니면 null
     */
    private OrderConfirmedEvent applyVerifiedSuccess(Long attemptId, PaymentFinalResult result) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId)
            .orElseThrow(() -> new IllegalStateException("[id = " + attemptId + "] 결제 시도를 찾을 수 없습니다."));

        int settled = paymentAttemptRepository.settleIfOpen(
            attemptId, result.status(), result.failureReason(), result.transactionKey());
        if (settled == 0) {
            return null;
        }

        // 확정 시각은 밀리초로 잘라 한 번만 만든다. 저장(confirmedAt·updatedAt)과 발행 payload 가 같은 값을 쓴다 (6주차 설계 D2).
        ZonedDateTime at = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        OrderConfirmedEvent firstConfirmation = null;
        int confirmedRows = orderRepository.finishIfPaymentPending(
            attempt.getOrderId(), Order.OrderStatus.CONFIRMED, attempt.getAmount(), at);
        if (confirmedRows == 1) {
            // 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로 confirmedAt 은 엔티티가 아니라 위의 at 을 쓰고, 품목만 DB 에서 읽는다 (D4).
            Order order = orderRepository.findByIdWithItems(attempt.getOrderId())
                .orElseThrow(() -> new IllegalStateException("[id = " + attempt.getOrderId() + "] 주문을 찾을 수 없습니다."));
            firstConfirmation = OrderConfirmedEvent.of(attempt.getOrderId(), at, order.getItems());
        }

        for (StockReservation reservation : stockReservationRepository.findAllByOrderId(attempt.getOrderId())) {
            stockReservationRepository.consumeIfReserved(reservation.getId());
        }
        return firstConfirmation;
    }

    /**
     * PaymentFacade.publishAfterCommit 247~258행 그대로. 이 시점은 확정 TX 가 이미 commit 된 뒤다.
     * 발행 실패·미확인·예상 못한 오류는 ERROR 로그로만 드러내고 밖으로 던지지 않는다. 주문 상태를 되돌리지 않는다 (D7).
     */
    private void publishAfterCommit(OrderConfirmedEvent event) {
        try {
            orderConfirmedPublisher.publish(event);
        } catch (OrderConfirmedPublishException e) {
            log.error("OrderConfirmed 발행 {}. outcome={}, eventId={}, orderId={}. 주문은 CONFIRMED 로 유지한다.",
                e.getOutcome() == OrderConfirmedPublishException.Outcome.FAILED ? "실패 확정" : "결과 미확인",
                e.getOutcome(), e.getEventId(), e.getOrderId(), e);
        } catch (RuntimeException e) {
            log.error("OrderConfirmed 발행 중 예상 못한 오류. eventId={}, orderId={}. 주문은 CONFIRMED 로 유지한다.",
                event.eventId(), event.orderId(), e);
        }
    }
}
