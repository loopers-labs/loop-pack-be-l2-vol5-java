package com.loopers.application.payment;

import com.loopers.application.order.event.OrderEventPublisher;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentLookupInfo;
import com.loopers.application.payment.dto.PaymentStatusUpdateResult;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * PG 에 결제 상태를 물어 결과를 반영하는 유스케이스(고객의 확인, PG 의 알림). 메소드에 트랜잭션을 열지 않고 읽기와 반영
 * 구간만 {@link TransactionTemplate} 으로 감싼다. 결제를 시작하는 확정 요청은 {@code OrderFacade.confirm} 이다.
 */
@Component
public class PaymentResultLookupFacade {

    private final PaymentGateway paymentGateway;
    private final PaymentStatusUpdater paymentStatusUpdater;
    private final OrderService orderService;
    private final PaymentAttemptService paymentAttemptService;
    private final OrderEventPublisher orderEventPublisher;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;

    public PaymentResultLookupFacade(
        PaymentGateway paymentGateway,
        PaymentStatusUpdater paymentStatusUpdater,
        OrderService orderService,
        PaymentAttemptService paymentAttemptService,
        OrderEventPublisher orderEventPublisher,
        PlatformTransactionManager transactionManager
    ) {
        this.paymentGateway = paymentGateway;
        this.paymentStatusUpdater = paymentStatusUpdater;
        this.orderService = orderService;
        this.paymentAttemptService = paymentAttemptService;
        this.orderEventPublisher = orderEventPublisher;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** 본인 주문의 결제 결과를 PG 에 다시 물어 반영한다(알림이 없거나 응답을 잃었을 때의 기본 복구 경로). create 는 다시 보내지 않는다. */
    public PaymentLookupInfo lookupMyPaymentResult(Long userId, Long orderId) {
        PaymentAttemptInfo target = readOnlyTransaction.execute(status -> findMine(userId, orderId));
        return syncWithPg(target, () -> target.transactionKey() != null
            ? paymentGateway.lookupPaymentStatusByTransactionKey(target.transactionKey())
            : paymentGateway.lookupPaymentStatusByOrder(target.providerOrderId()));
    }

    /** PG 의 결과 알림을 받는다. 알림은 신호일 뿐이라 본문의 상태는 쓰지 않고, 알림의 거래 키로 PG 를 조회해 확인한 사실만 반영한다. */
    public PaymentLookupInfo lookupPaymentResultOnPgCallback(String providerOrderId, String transactionKey) {
        PaymentAttemptInfo target = readOnlyTransaction.execute(status -> findByProviderOrderId(providerOrderId));
        return syncWithPg(target, () -> paymentGateway.lookupPaymentStatusByTransactionKey(transactionKey));
    }

    // 종결된 시도는 PG 를 다시 부르지 않는다. 묻지 못하면 저장된 상태를 그대로 돌려주고 아무것도 정하지 않는다.
    private PaymentLookupInfo syncWithPg(PaymentAttemptInfo target, Supplier<PgPaymentStatusLookupResult> askPg) {
        if (target.isTerminated()) {
            return new PaymentLookupInfo(target, false);
        }
        PgPaymentStatusLookupResult lookupResult = askPg.get();
        if (lookupResult.outcome() == PgPaymentStatusLookupResult.Outcome.LOOKUP_UNAVAILABLE) {
            return new PaymentLookupInfo(target, true);
        }
        PaymentStatusUpdateResult updated = writeTransaction.execute(
            status -> paymentStatusUpdater.updateByStatusLookupResult(target.orderId(), lookupResult)
        );
        // execute 가 반환됐으면 commit 이 끝났다. 처음 확정한 반영만 알리고, 전송 실패가 확정을 되돌리지 않는다.
        if (updated.confirmed() != null) {
            orderEventPublisher.publish(updated.confirmed());
        }
        return new PaymentLookupInfo(updated.attempt(), false);
    }

    // 없는 주문과 타인의 주문은 NOT_FOUND, 결제를 시작하지 않은 주문은 CONFLICT 다.
    private PaymentAttemptInfo findMine(Long userId, Long orderId) {
        Order order = orderService.getMine(orderId, userId);
        PaymentAttempt attempt = paymentAttemptService.findByOrderId(order.getId()).orElseThrow(
            () -> new CoreException(ErrorType.CONFLICT, "결제를 시작한 주문이 아닙니다.")
        );
        return PaymentAttemptInfo.of(order, attempt);
    }

    // 알림에는 요청자가 없으므로 소유권 확인 없이 PG 용 주문 식별자로 찾는다.
    private PaymentAttemptInfo findByProviderOrderId(String providerOrderId) {
        PaymentAttempt attempt = paymentAttemptService.findByProviderOrderId(providerOrderId).orElseThrow(
            () -> new CoreException(ErrorType.NOT_FOUND, "결제 시도를 찾을 수 없습니다.")
        );
        return PaymentAttemptInfo.of(orderService.get(attempt.getOrderId()), attempt);
    }
}
