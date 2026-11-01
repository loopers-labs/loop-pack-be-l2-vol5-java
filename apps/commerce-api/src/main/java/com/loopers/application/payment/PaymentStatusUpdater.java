package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentStatusUpdateResult;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderStockService;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentAnomaly;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.PaymentAttemptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * PG 결과를 시도·주문·재고에 반영한다(TX B 의 내용). 트랜잭션은 호출하는 Facade 가 연다. 항상 주문 행을 먼저
 * 잠그고 시도를 읽으므로 같은 주문의 여러 입구가 줄을 서고, 이미 나아간 상태는 늦은 응답이 되돌리지 못한다.
 * 상충하는 응답은 상태를 덮어쓰지 않고 이상 징후로만 남긴다.
 */
@RequiredArgsConstructor
@Component
public class PaymentStatusUpdater {

    private final OrderService orderService;
    private final PaymentAttemptService paymentAttemptService;
    private final OrderStockService orderStockService;
    private final Clock clock;

    /** 결제 생성 요청의 결과를 반영한다. */
    public PaymentAttemptInfo updateByPaymentRequestResult(Long orderId, PgPaymentRequestResult result) {
        Order order = orderService.getForUpdate(orderId);
        PaymentAttempt attempt = paymentAttemptService.getByOrderId(orderId);
        switch (result.outcome()) {
            case ACCEPTED -> applyAccepted(attempt, result.transaction());
            case UNKNOWN -> applyUnknown(attempt);
            case REJECTED_WITHOUT_TRANSACTION -> applyRejected(order, attempt);
        }
        return PaymentAttemptInfo.of(order, attempt);
    }

    // PG 가 거래를 만들었다. 거래 키를 기록한다. 이미 나아간 상태는 되돌리지 않고, 다른 거래라면 이상 징후로 남긴다.
    private void applyAccepted(PaymentAttempt attempt, PgTransactionInfo transaction) {
        String key = transaction.transactionKey();
        switch (attempt.getStatus()) {
            case REQUESTED, UNKNOWN -> attempt.markPending(key);
            case PENDING, SUCCEEDED -> {
                if (!key.equals(attempt.getTransactionKey())) {
                    attempt.recordAnomaly(PaymentAnomaly.MISMATCH);
                }
            }
            case FAILED -> {
                if (!key.equals(attempt.getTransactionKey())) {
                    attempt.recordAnomaly(PaymentAnomaly.CONFLICTING_RESULT);
                }
            }
        }
    }

    // 거래 생성 여부를 모른다. 처음 발송한 직후에만 결과 미확인으로 남기고, 이미 나아간 상태는 되돌리지 않는다.
    private void applyUnknown(PaymentAttempt attempt) {
        switch (attempt.getStatus()) {
            case REQUESTED -> attempt.markUnknown();
            case UNKNOWN, PENDING, SUCCEEDED, FAILED -> { }
        }
    }

    // 거래가 없다는 확정 거절. 같은 거절을 다시 적용해도 효과가 없고, 거래를 만들었다는 결과와 충돌하면 이상 징후로만 남긴다.
    private void applyRejected(Order order, PaymentAttempt attempt) {
        switch (attempt.getStatus()) {
            case REQUESTED, UNKNOWN -> fail(order, attempt, null);
            case FAILED -> { }
            case PENDING, SUCCEEDED -> attempt.recordAnomaly(PaymentAnomaly.CONFLICTING_RESULT);
        }
    }

    /**
     * PG 에 물어 확인한 사실을 반영한다. confirm 직후·고객의 확인·PG 의 알림이 모두 여기를 거치므로 입구의 순서가 바뀌어도
     * 같은 결과로 수렴한다.
     */
    public PaymentStatusUpdateResult updateByStatusLookupResult(Long orderId, PgPaymentStatusLookupResult lookupResult) {
        Order order = orderService.getForUpdate(orderId);
        PaymentAttempt attempt = paymentAttemptService.getByOrderId(orderId);
        boolean wasConfirmed = order.getStatus() == OrderStatus.CONFIRMED;
        switch (lookupResult.outcome()) {
            case KEY_NOT_FOUND -> recordUnlessTerminated(attempt, PaymentAnomaly.TRANSACTION_NOT_FOUND);
            case ANSWERED -> applyAnswer(order, attempt, lookupResult.transactions());
            // 묻지 못한 것은 아무것도 알려 주지 않는다. 상태도 재고도 그대로다.
            case LOOKUP_UNAVAILABLE -> { }
        }
        // 품목은 LAZY 라 트랜잭션 안에서 payload 를 만들어 둔다.
        OrderConfirmedInfo confirmed = !wasConfirmed && order.getStatus() == OrderStatus.CONFIRMED
            ? OrderConfirmedInfo.from(order)
            : null;
        return new PaymentStatusUpdateResult(PaymentAttemptInfo.of(order, attempt), confirmed);
    }

    // 거래가 0건이면 아직 안 보이는 것일 수 있어 아무것도 정하지 않는다. 여러 건이면 하나를 임의로 고르지 않는다.
    private void applyAnswer(Order order, PaymentAttempt attempt, List<PgTransactionInfo> transactions) {
        switch (transactions.size()) {
            case 0 -> { }
            case 1 -> applyTransaction(order, attempt, transactions.get(0));
            default -> recordUnlessTerminated(attempt, PaymentAnomaly.MULTIPLE_TRANSACTIONS);
        }
    }

    private void applyTransaction(Order order, PaymentAttempt attempt, PgTransactionInfo transaction) {
        // 다른 주문의 거래, 금액이 다른 거래, 이미 아는 거래와 다른 거래는 이 시도에 적용하지 않는다.
        if (!belongsTo(attempt, transaction)) {
            attempt.recordAnomaly(PaymentAnomaly.MISMATCH);
            return;
        }
        if (attempt.isTerminated()) {
            applyToTerminated(attempt, transaction);
            return;
        }
        String key = transaction.transactionKey();
        switch (transaction.status()) {
            // 처리 중이라고 확인했을 뿐이다. 거래 키만 알게 되고 종결하지 않는다.
            case PENDING -> {
                if (attempt.getStatus() != PaymentAttemptStatus.PENDING) {
                    attempt.markPending(key);
                }
            }
            case SUCCESS -> succeed(order, attempt, key);
            case FAILED -> fail(order, attempt, key);
        }
    }

    private boolean belongsTo(PaymentAttempt attempt, PgTransactionInfo transaction) {
        return transaction.providerOrderId().equals(attempt.getProviderOrderId())
            && transaction.amount() == attempt.getAmount().amount()
            && (attempt.getTransactionKey() == null || attempt.getTransactionKey().equals(transaction.transactionKey()));
    }

    // 종결된 시도는 같은 결과가 다시 와도 효과가 없고, 다른 결과가 오면 덮어쓰지 않고 상충만 남긴다.
    // 종결 뒤에 도착한 처리 중 관찰은 낡은 정보라 무시한다.
    private void applyToTerminated(PaymentAttempt attempt, PgTransactionInfo transaction) {
        if (transaction.status() == PgStatus.PENDING) {
            return;
        }
        if (!isSameResult(attempt, transaction)) {
            attempt.recordAnomaly(PaymentAnomaly.CONFLICTING_RESULT);
        }
    }

    private boolean isSameResult(PaymentAttempt attempt, PgTransactionInfo transaction) {
        boolean sameStatus = (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED && transaction.status() == PgStatus.SUCCESS)
            || (attempt.getStatus() == PaymentAttemptStatus.FAILED && transaction.status() == PgStatus.FAILED);
        return sameStatus && transaction.transactionKey().equals(attempt.getTransactionKey());
    }

    private void recordUnlessTerminated(PaymentAttempt attempt, PaymentAnomaly anomaly) {
        if (!attempt.isTerminated()) {
            attempt.recordAnomaly(anomaly);
        }
    }

    // 재고는 시작할 때 이미 차감했다. 다시 차감하지 않는다.
    private void succeed(Order order, PaymentAttempt attempt, String transactionKey) {
        attempt.markSucceeded(transactionKey);
        // DB 가 저장하는 마이크로초로 맞춰 두면 이벤트의 확정 시각이 저장된 값과 같다.
        ZonedDateTime confirmedAt = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        order.completePayment(new PaymentResult(order.getTotalAmount()), confirmedAt);
    }

    // 시도 FAILED 와 주문 PAYMENT_FAILED 전이가 같이 일어나는 한 곳에서만 재고를 한 번 돌려준다.
    private void fail(Order order, PaymentAttempt attempt, String transactionKey) {
        attempt.markFailed(transactionKey);
        order.failPayment();
        orderStockService.restoreStock(order);
    }
}
