package com.loopers.application.payment;

import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgTransactionInfo;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.function.Supplier;

/**
 * 결제 경계 사건을 이름 붙은 key-value 로 남긴다. event·키 이름은 api·streamer 가 함께 쓰는 진단 계약이다(설계 문서 2장).
 * 콘솔 메시지에도 핵심 값을 적어 key-value 를 보여 주지 않는 plain 출력에서도 읽힌다.
 */
@Slf4j
public final class PaymentBoundaryLog {

    public static final String ORDER_ID = "orderId";

    private PaymentBoundaryLog() {}

    /** 결제 처리 범위에 orderId 를 MDC 로 둔다. 닫으면 지워져 스레드 풀의 다음 작업에 남지 않는다. */
    public static MDC.MDCCloseable orderScope(Long orderId) {
        return MDC.putCloseable(ORDER_ID, String.valueOf(orderId));
    }

    public static void lookupResult(String providerOrderId, PgPaymentStatusLookupResult result) {
        LoggingEventBuilder event = result.outcome() == PgPaymentStatusLookupResult.Outcome.LOOKUP_UNAVAILABLE
            ? log.atWarn()
            : log.atInfo();
        event.addKeyValue("event", "payment.lookup.result")
            .addKeyValue("providerOrderId", providerOrderId)
            .addKeyValue("outcome", result.outcome())
            .addKeyValue("count", result.transactions().size());
        if (result.transactions().size() == 1) {
            PgTransactionInfo transaction = result.transactions().get(0);
            event.addKeyValue("pgStatus", transaction.status())
                .addKeyValue("transactionKey", transaction.transactionKey());
        }
        event.log("PG 결제 상태 조회: outcome={}, count={}", result.outcome(), result.transactions().size());
    }

    /** 결과 반영 TX 를 실행하고 commit·rollback 을 남긴다. 예외는 삼키지 않고 그대로 던진다. */
    public static PaymentAttemptInfo applyLogged(PaymentAttemptInfo before, Supplier<PaymentAttemptInfo> applyInTransaction) {
        PaymentAttemptInfo after;
        try {
            after = applyInTransaction.get();
        } catch (RuntimeException e) {
            log.atError()
                .addKeyValue("event", "payment.apply.rolled_back")
                .addKeyValue("providerOrderId", before.providerOrderId())
                .addKeyValue("from", before.attemptStatus())
                .addKeyValue("exception", e.getClass().getSimpleName())
                .setCause(e)
                .log("결제 결과 반영 rollback: from={}, exception={}", before.attemptStatus(), e.getClass().getSimpleName());
            throw e;
        }
        log.atInfo()
            .addKeyValue("event", "payment.apply.committed")
            .addKeyValue("providerOrderId", after.providerOrderId())
            .addKeyValue("from", before.attemptStatus())
            .addKeyValue("to", after.attemptStatus())
            .addKeyValue("orderStatus", after.orderStatus())
            .log("결제 결과 반영: {} -> {}, order={}", before.attemptStatus(), after.attemptStatus(), after.orderStatus());
        return after;
    }
}
