package com.loopers.application.payment;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgLookupResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 주문 확정의 결제를 PG로 시작하고 결과를 확인한다 (design.md 9-4절). 이 클래스에는 트랜잭션이 없다.
 * DB를 바꾸는 단계는 {@link PaymentTransactions}의 짧은 트랜잭션 둘이고, PG HTTP 호출은 그 사이에서 트랜잭션·재고 잠금 없이 한다.
 *
 * <p>create는 한 번만 보낸다. TX A를 commit한 실행만 보내고, 응답을 잃어도(timeout·끊김) 다시 보내지 않는다.
 * 이후의 확인은 기존 거래 조회({@link #reconcile}, {@link #handleCallback})로만 한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentFacade {

    private final PaymentTransactions transactions;
    private final PaymentGateway paymentGateway;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * 본인 DRAFT 주문의 PG 결제를 시작한다. 금액은 받지 않고 저장된 주문 합계로 정한다.
     * 같은 요청(키·카드 종류)의 반복은 저장된 상태를 돌려주고 PG를 다시 부르지 않는다.
     */
    public PaymentOutcome confirm(Long orderId, Long userId, String paymentAttemptKey, String cardType, String cardNo) {
        // 입력은 TX A 전에 확인한다. TX A를 commit한 뒤 요청 객체를 만들다 실패하면 발송 기록만 남기 때문이다
        requireText(paymentAttemptKey, "결제 시도 키");
        requireText(cardType, "카드 종류");
        requireText(cardNo, "카드 번호");

        PaymentTransactions.Started started = startPayment(orderId, userId, paymentAttemptKey, cardType);
        if (!started.sendRequired()) {
            return started.outcome();
        }

        // TX A는 commit됐고 아무 트랜잭션도 열려 있지 않다. 여기서 응답을 잃어도 시도는 REQUESTED로 남아 조회로 확인한다
        PgCreateResult result = paymentGateway.requestPayment(
            new PgPaymentRequest(started.providerOrderId(), cardType, cardNo, started.amount())
        );
        return publishAfterCommit(transactions.applyCreate(orderId, result));
    }

    /** 본인 미완료 시도의 기존 거래를 조회해 결과를 적용한다. 새 결제 요청은 만들지 않는다. */
    public PaymentOutcome reconcile(Long orderId, Long userId) {
        PaymentTransactions.Target target = transactions.reconcileTarget(orderId, userId);
        if (target.outcome().settled()) {
            return target.outcome();
        }
        PgLookupResult lookup = target.transactionKey() != null
            ? paymentGateway.findTransaction(target.transactionKey())
            : paymentGateway.findTransactionsByOrder(target.providerOrderId());
        return applyLookup(target.orderId(), lookup);
    }

    /**
     * PG의 결과 알림을 확인 요청의 계기로만 쓴다. 알림 본문의 성공·실패 값은 받지 않고, 알림이 가리킨 거래를 PG에서 직접 조회한 결과만 적용한다.
     * 그래서 조작한 알림 본문만으로는 주문을 확정할 수 없다.
     */
    public PaymentOutcome handleCallback(String transactionKey, String providerOrderId) {
        requireText(transactionKey, "거래 키");
        requireText(providerOrderId, "PG 주문 번호");

        PaymentTransactions.Target target = transactions.callbackTarget(providerOrderId);
        if (target.outcome().settled()) {
            return target.outcome();
        }
        return applyLookup(target.orderId(), paymentGateway.findTransaction(transactionKey));
    }

    // 키 유일 제약 위반은 TX A 전체가 rollback된 뒤 여기로 올라온다. 두 주문이 같은 키를 동시에 쓴 경우라서 충돌로 바꿔 알린다
    private PaymentTransactions.Started startPayment(Long orderId, Long userId, String paymentAttemptKey, String cardType) {
        try {
            return transactions.start(orderId, userId, paymentAttemptKey, cardType);
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.CONFLICT, "다른 주문에서 이미 사용한 결제 시도 키입니다.");
        }
    }

    private PaymentOutcome applyLookup(Long orderId, PgLookupResult lookup) {
        return switch (lookup) {
            case PgLookupResult.Transactions found -> publishAfterCommit(transactions.applyLookup(orderId, found.transactions()));
            case PgLookupResult.Unavailable unavailable -> transactions.currentWithLookupUnavailable(orderId);
        };
    }

    /**
     * 결과 적용 트랜잭션이 commit된 <b>뒤에</b>, 이번 적용이 주문을 처음 확정한 경우에만 확정 이벤트를 발행한다.
     * confirm·reconcile·callback 어느 경로가 먼저 확정해도 이 한 곳을 지나고, 이미 종결된 시도의 반복 적용은 이벤트가 없다.
     *
     * <p>발행의 성공·실패와 상관없이 저장된 주문 결과를 그대로 돌려준다. 발행이 실패해도 확정된 주문을 되돌리거나 PG를 다시 부르지 않는다.
     * 이 경로에는 commit과 발행을 묶는 장치가 없어서, 발행 실패는 이벤트 누락으로 남는다. 누락은 로그로 드러내며 개선은 다음 주(Outbox)의 몫이다.
     */
    private PaymentOutcome publishAfterCommit(PaymentTransactions.Applied applied) {
        OrderConfirmedEvent event = applied.confirmedEvent();
        if (event != null) {
            report(event, publish(event));
        }
        return applied.outcome();
    }

    private OrderEventPublication publish(OrderConfirmedEvent event) {
        try {
            return orderEventPublisher.publish(event);
        } catch (RuntimeException e) {
            // 계약상 던지지 않지만, 던져도 이미 commit된 주문 응답을 바꾸지 않는다
            return new OrderEventPublication.Failed("발행 중 예외: " + e);
        }
    }

    private static void report(OrderConfirmedEvent event, OrderEventPublication publication) {
        switch (publication) {
            case OrderEventPublication.Sent sent -> log.info(
                "OrderConfirmed 발행 성공: eventId={}, orderId={}, topic={}, partition={}, offset={}",
                event.eventId(), event.orderId(), sent.topic(), sent.partition(), sent.offset()
            );
            case OrderEventPublication.Failed failed -> log.error(
                "OrderConfirmed 발행 실패(주문은 확정된 상태, broker record 없음): eventId={}, orderId={}, reason={}",
                event.eventId(), event.orderId(), failed.reason()
            );
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, name + "는 비어있을 수 없습니다.");
        }
    }
}
