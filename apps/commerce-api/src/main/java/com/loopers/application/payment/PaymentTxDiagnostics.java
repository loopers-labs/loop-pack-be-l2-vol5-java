package com.loopers.application.payment;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.payment.ObservationDecision;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.support.logging.DiagnosticLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 결제 TX 메서드의 진단 전용 기록(checkout-incident §4.3). 비즈니스 판정을 하지 않는다.
 * 실제 commit/rollback 은 메서드 반환이 아니라 afterCompletion 으로만 기록한다.
 * 진단 실패는 RuntimeException 만 국소 처리해 업무 결과를 바꾸지 않는다.
 */
@Slf4j
@Component
public class PaymentTxDiagnostics {

    public enum Operation {
        BEGIN_CONFIRMATION,
        PREPARE_VERIFICATION,
        APPLY_OBSERVATION,
        RECORD_CONSTRAINT_CONFLICT
    }

    static final String TX_METHOD_ENTER = "TX_METHOD_ENTER";
    static final String TX_COMPLETION = "TX_COMPLETION";
    static final String TX_OBSERVATION_UNAVAILABLE = "TX_OBSERVATION_UNAVAILABLE";
    static final String PAYMENT_CONTEXT = "PAYMENT_CONTEXT";
    static final String PAYMENT_APPLY_INPUT = "PAYMENT_APPLY_INPUT";
    static final String PAYMENT_APPLY_DECISION = "PAYMENT_APPLY_DECISION";
    static final String NO_ACTIVE_SYNCHRONIZATION = "NO_ACTIVE_SYNCHRONIZATION";
    static final String OBSERVATION_FAILED = "OBSERVATION_FAILED";

    private static final Pattern ATTEMPT_KEY = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private final Consumer<TransactionSynchronization> registrar;

    public PaymentTxDiagnostics() {
        this(TransactionSynchronizationManager::registerSynchronization);
    }

    /** 등록 실패 검증용 주입점. 운영 bean 은 Spring 기본 등록을 쓴다. */
    PaymentTxDiagnostics(Consumer<TransactionSynchronization> registrar) {
        this.registrar = registrar;
    }

    /** 메서드 첫 SQL 전에 부른다. 실제 TX·synchronization 이 없으면 가짜 종료 대신 UNAVAILABLE 을 남긴다. */
    public String observe(Operation operation, Long orderId, Long paymentId, ObservationSource source) {
        String observationId = UUID.randomUUID().toString();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("observationId", observationId);
        context.put("operation", operation == null ? null : operation.name());
        context.put("orderId", orderId);
        context.put("paymentId", paymentId);
        context.put("source", source == null ? null : source.name());
        Map<String, Object> frozen = Collections.unmodifiableMap(context);
        DiagnosticLog.info(log, TX_METHOD_ENTER, frozen);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
                registrar.accept(new Completion(frozen));
            } else {
                unavailable(frozen, NO_ACTIVE_SYNCHRONIZATION, null);
            }
        } catch (RuntimeException e) {
            unavailable(frozen, OBSERVATION_FAILED, e.getClass().getName());
        }
        return observationId;
    }

    public void paymentContext(String observationId, OrderModel order, PaymentModel payment) {
        paymentContext(observationId, order, payment, null, null);
    }

    /** 조회 준비는 선택한 조회 종류·대상을 함께 남긴다. 콜백의 거래 키는 검증 전 입력이라 형식만 확인한다. */
    public void paymentContext(String observationId, OrderModel order, PaymentModel payment, String lookupKind,
                               String lookupTarget) {
        safely(observationId, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("observationId", observationId);
            fields.put("orderId", order.getId());
            fields.put("orderStatus", order.getStatus().name());
            fields.put("paymentId", payment.getId());
            fields.put("attemptKey", ATTEMPT_KEY.matcher(payment.getAttemptKey()).matches()
                ? payment.getAttemptKey() : DiagnosticLog.INVALID);
            fields.put("providerOrderId", DiagnosticLog.providerOrderId(payment.getProviderOrderId()));
            fields.put("transactionKey", DiagnosticLog.transactionKey(payment.getTransactionKey()));
            fields.put("amount", payment.getAmount().toWon());
            fields.put("paymentStatus", payment.getStatus().name());
            if (lookupKind != null) {
                fields.put("lookupKind", lookupKind);
                fields.put("lookupTarget", "TRANSACTION_KEY".equals(lookupKind)
                    ? DiagnosticLog.transactionKey(lookupTarget) : DiagnosticLog.providerOrderId(lookupTarget));
            }
            DiagnosticLog.info(log, PAYMENT_CONTEXT, fields);
        });
    }

    public void applyInput(String observationId, PaymentObservation observation) {
        safely(observationId, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("observationId", observationId);
            fields.put("classification", observation.getClass().getSimpleName());
            fields.put("source", observation.source().name());
            switch (observation) {
                case PaymentObservation.TransactionObserved observed -> {
                    fields.put("transactionKey", DiagnosticLog.transactionKey(observed.transactionKey()));
                    fields.put("providerOrderId", DiagnosticLog.providerOrderId(observed.providerOrderId()));
                    fields.put("amount", observed.amount());
                    fields.put("status", observed.status().name());
                }
                case PaymentObservation.Conflict conflict -> fields.put("conflictCode", conflict.code().name());
                case PaymentObservation.RejectedNoEffect rejected -> {
                    fields.put("providerOrderId", DiagnosticLog.providerOrderId(rejected.providerOrderId()));
                    fields.put("amount", rejected.amount());
                }
                case PaymentObservation.LocalNotDispatched notDispatched ->
                    fields.put("notDispatchedReason", notDispatched.reason().name());
                case PaymentObservation.Unavailable unavailable -> {
                }
            }
            DiagnosticLog.info(log, PAYMENT_APPLY_INPUT, fields);
        });
    }

    /** 판정 직후의 결제 상태다. commit 전이므로 이 기록을 확정 성공으로 읽지 않는다. */
    public void applyDecision(String observationId, ObservationDecision decision, PaymentModel payment) {
        safely(observationId, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("observationId", observationId);
            fields.put("effect", decision.effect().name());
            fields.put("conflictCode", decision.conflictCode() == null ? null : decision.conflictCode().name());
            fields.put("paymentStatus", payment.getStatus().name());
            DiagnosticLog.info(log, PAYMENT_APPLY_DECISION, fields);
        });
    }

    private static void unavailable(Map<String, Object> context, String reason, String exception) {
        Map<String, Object> fields = new LinkedHashMap<>(context);
        fields.put("reason", reason);
        fields.put("exception", exception);
        DiagnosticLog.info(log, TX_OBSERVATION_UNAVAILABLE, fields);
    }

    private static void safely(String observationId, Runnable diagnostic) {
        try {
            diagnostic.run();
        } catch (RuntimeException e) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("observationId", observationId);
            unavailable(context, OBSERVATION_FAILED, e.getClass().getName());
        }
    }

    /** 불변 context 만 들고 있다가 실제 종료 상태를 기록한다. 추가 조회·I/O·새 TX 는 하지 않는다. */
    private static final class Completion implements TransactionSynchronization {

        private final Map<String, Object> context;

        Completion(Map<String, Object> context) {
            this.context = context;
        }

        @Override
        public void afterCompletion(int status) {
            Map<String, Object> fields = new LinkedHashMap<>(context);
            fields.put("outcome", switch (status) {
                case STATUS_COMMITTED -> "COMMITTED";
                case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                default -> "UNKNOWN";
            });
            DiagnosticLog.info(log, TX_COMPLETION, fields);
        }
    }
}
