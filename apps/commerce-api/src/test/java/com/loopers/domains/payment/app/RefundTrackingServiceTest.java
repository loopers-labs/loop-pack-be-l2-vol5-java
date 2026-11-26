package com.loopers.domains.payment.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.domains.payment.app.port.PaymentCancelCommand;
import com.loopers.domains.payment.app.port.PaymentCancelResult;
import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.PaymentTransaction;
import com.loopers.domains.payment.app.port.PaymentTransactionStatus;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.support.pg.MutablePaymentClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 포트만 fake/mock으로 두고 환불 추적 한 회차의 판단과 경계를 본다(10주차 5단계 계획 2-1·2-2).
// 트랜잭션 관리자는 commit·rollback을 사건 목록에 남겨, 환불 요청 상태 저장이 commit된 뒤에 취소가 나가는지 순서로 확인한다.
class RefundTrackingServiceTest {
    private static final Instant OPENED_AT = Instant.parse("2026-10-21T00:02:00Z");
    private static final Instant NOW = OPENED_AT.plus(Duration.ofMinutes(5));
    private static final String PG_A = "pg-a";
    private static final String PG_B = "pg-b";
    private static final Money AMOUNT = new Money(2000);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Set<String> blockedProviders = new HashSet<>();
    private final List<RefundOutcomeRecorder.Outcome> outcomes = new CopyOnWriteArrayList<>();
    private InMemoryRefundCases refundCases;
    private PaymentGateway gatewayA;
    private PaymentGateway gatewayB;
    private MutablePaymentClock clock;
    private RefundTrackingService service;

    @BeforeEach
    void prepare() {
        refundCases = new InMemoryRefundCases(events);
        gatewayA = gateway(PG_A);
        gatewayB = gateway(PG_B);
        clock = new MutablePaymentClock();
        clock.set(NOW);
        service = new RefundTrackingService(refundCases, new PaymentGatewayRegistry(List.of(gatewayA, gatewayB), List.of(PG_A, PG_B)),
            blockedProviders::contains, new TransactionTemplate(new RecordingTransactions(events)), clock,
            outcome -> {
                events.add("outcome:" + outcome);
                outcomes.add(outcome);
            });
    }

    @Test
    @DisplayName("추적 중 대상의 조회가 금액·주문·키가 맞는 단일 SUCCESS면 REFUND_REQUESTED를 저장·commit한 뒤에 취소를 한 번 보내고 취소 확인으로 REFUNDED가 된다")
    void requestsRefundAfterCommitAndConcludes() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Canceled(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        assertThat(events).containsSubsequence("save:1:REFUND_REQUESTED", "commit", "cancel:1", "save:1:REFUNDED", "commit",
            "outcome:REFUNDED");
        assertThat(events.indexOf("cancel:1")).isGreaterThan(events.indexOf("save:1:REFUND_REQUESTED") + 1);
        verify(gatewayA, times(1)).cancel(new PaymentCancelCommand(providerOrderId(1), "TR-1", AMOUNT));
        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
        assertThat(refundCases.get(1).getRefundRequestedAt()).isEqualTo(NOW);
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.REFUNDED);
    }

    @Test
    @DisplayName("키 없이 만료된 대상은 주문 번호로 조회해 찾은 일치 거래 키를 대상에 남기고 그 키로 취소한다")
    void cancelsWithKeyFoundByLookup() {
        refundCases.put(tracking(1, PG_A, null));
        lookup(gatewayA, 1, found(tx("TR-9", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Canceled(tx("TR-9", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        verify(gatewayA).verifyOrder(providerOrderId(1), null);
        verify(gatewayA, times(1)).cancel(new PaymentCancelCommand(providerOrderId(1), "TR-9", AMOUNT));
        assertThat(refundCases.get(1).getTransactionKey()).isEqualTo("TR-9");
        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
    }

    @Test
    @DisplayName("취소 결과를 모르면 REFUND_UNKNOWN으로 두고 결론 counter를 세지 않는다")
    void marksUnknownCancel() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Unknown("timeout"));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        verify(gatewayA, times(1)).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    // [판단 필요] NotSent도 요청 전 기록이 이미 commit된 뒤라 다시 보내지 않고 결과 모름처럼 조회로만 확인한다(안전한 기본값).
    @Test
    @DisplayName("취소가 PG에 닿지 않았다고 분류돼도 REFUND_REQUESTED가 이미 commit됐으므로 다시 보내지 않고 REFUND_UNKNOWN으로 둔다")
    void treatsNotSentCancelAsUnknown() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.NotSent("connect refused"));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        verify(gatewayA, times(1)).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @Test
    @DisplayName("PG가 효과 없이 취소를 거절하면 자동으로 더 하지 않고 사유 REFUND_REJECTED로 사람에게 넘기며 handed_to_human을 센다")
    void handsRejectedCancelToHuman() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.RejectedNoEffect("TEST_PG_CANCEL_REJECTED"));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCases.get(1).getReason()).isEqualTo(RefundCase.REFUND_REJECTED);
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.HANDED_TO_HUMAN);
    }

    @Test
    @DisplayName("취소 호출이 예외로 끝나면 다시 보내지 않고 REFUND_UNKNOWN으로 두며 같은 회차의 다음 대상은 계속 처리한다")
    void marksUnknownAndContinuesWhenCancelThrows() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        refundCases.put(trackingAt(2, PG_A, "TR-2", NOW.minusSeconds(1)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        lookup(gatewayA, 2, found(tx("TR-2", 2, AMOUNT, PaymentTransactionStatus.FAILED)));
        when(gatewayA.cancel(any())).thenAnswer(invocation -> {
            events.add("cancel:" + orderIdOf(invocation.getArgument(0)));
            throw new IllegalStateException("주입된 취소 중단");
        });

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        assertThat(events).containsSubsequence("save:1:REFUND_REQUESTED", "commit", "cancel:1", "save:1:REFUND_UNKNOWN", "commit");
        assertThat(events.stream().filter("cancel:1"::equals)).hasSize(1);
        assertThat(refundCases.get(2).getStatus()).isEqualTo(RefundCase.Status.CLOSED_NO_CHARGE);
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.CLOSED_NO_CHARGE);
    }

    @ParameterizedTest(name = "PG 상태={0}")
    @EnumSource(value = PaymentTransactionStatus.class, names = {"FAILED", "CANCELED"})
    @DisplayName("추적 중 대상의 조회가 일치하는 단일 FAILED·CANCELED면 취소 없이 CLOSED_NO_CHARGE로 닫고 closed_no_charge를 센다")
    void closesWhenNoCharge(PaymentTransactionStatus status) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, status)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.CLOSED_NO_CHARGE);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.CLOSED_NO_CHARGE);
    }

    @ParameterizedTest(name = "조회 결과={0}")
    @ValueSource(strings = {"pending", "zero", "failed", "exception"})
    @DisplayName("PENDING·0건·조회 실패·조회 예외는 결론 근거가 아니라 TRACKING을 유지하고 조회 횟수를 1 늘려 1분 뒤 다시 본다")
    void keepsTrackingWhenInconclusive(String result) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        switch (result) {
            case "pending" -> lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.PENDING)));
            case "zero" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));
            case "failed" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Failed("500"));
            default -> when(gatewayA.verifyOrder(anyString(), any())).thenThrow(new IllegalStateException("주입된 조회 예외"));
        }

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @Test
    @DisplayName("게이트웨이가 회로 정책으로 PG를 부르지 않으면 조회 횟수는 그대로 두고 다음 조회만 미룬다")
    void postponesWhenLookupNotCalled() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, new PaymentTransactionsLookup.NotCalled());

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(refundCases.get(1).getCheckAttempts()).isZero();
        assertThat(refundCases.get(1).getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"amount", "order", "key", "multiple-all", "multiple-single"})
    @DisplayName("금액·주문·키가 다른 단일 거래나 복수 거래는 취소하지 않고 즉시 사유를 남겨 사람에게 넘기며 handed_to_human을 센다")
    void handsMismatchToHuman(String scene) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        PaymentTransaction matching = tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS);
        String expectedReason = RefundCase.TRANSACTION_MISMATCH;
        switch (scene) {
            case "amount" -> lookup(gatewayA, 1, found(tx("TR-1", 1, new Money(2001), PaymentTransactionStatus.SUCCESS)));
            case "order" -> lookup(gatewayA, 1, found(new PaymentTransaction("TR-1", providerOrderId(1) + "-X", AMOUNT,
                PaymentTransactionStatus.SUCCESS)));
            case "key" -> lookup(gatewayA, 1, found(tx("TR-OTHER", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
            case "multiple-all" -> {
                lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(matching,
                    tx("TR-2", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)), PaymentTransactionsLookup.Coverage.ALL));
                expectedReason = RefundCase.MULTIPLE_TRANSACTIONS;
            }
            default -> {
                lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(matching,
                    tx("TR-2", 1, AMOUNT, PaymentTransactionStatus.FAILED)), PaymentTransactionsLookup.Coverage.SINGLE));
                expectedReason = RefundCase.MULTIPLE_TRANSACTIONS;
            }
        }

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCases.get(1).getReason()).isEqualTo(expectedReason);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.HANDED_TO_HUMAN);
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 상태의 대상은 조회만 하고 CANCELED가 보이면 취소를 다시 보내지 않고 REFUNDED로 결론 낸다")
    void concludesRefundByLookupOnly(RefundCase.Status status) {
        refundCases.put(withStatus(1, PG_A, "TR-1", status));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
        verify(gatewayA).verifyOrder(providerOrderId(1), "TR-1");
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.REFUNDED);
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 상태의 대상이 아직 SUCCESS로 보이면 취소를 다시 보내지 않고 상태를 유지하며 조회 횟수만 늘린다")
    void neverResendsRefund(RefundCase.Status status) {
        refundCases.put(withStatus(1, PG_A, "TR-1", status));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(status);
        assertThat(refundCases.get(1).getCheckAttempts()).isEqualTo(1);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @ParameterizedTest(name = "상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"TRACKING", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("이관 기한이 지난 결론 전 대상은 조회하지 않고 사유 HANDOFF_DEADLINE으로 사람에게 넘기며 handed_to_human을 센다")
    void handsOffAtDeadline(RefundCase.Status status) {
        refundCases.put(withStatus(1, PG_A, "TR-1", status));
        clock.set(OPENED_AT.plus(Duration.ofDays(2)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCases.get(1).getReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        verify(gatewayA, never()).verifyOrder(anyString(), any());
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly(RefundOutcomeRecorder.Outcome.HANDED_TO_HUMAN);
    }

    @Test
    @DisplayName("조회 회로가 막힌 PG의 대상은 조회 없이 횟수 그대로 미루고, 다른 PG의 대상은 회차 상한 20건 안에서 조회한다")
    void respectsCircuitAndBatchLimit() {
        for (long orderId = 1; orderId <= 15; orderId++) {
            refundCases.put(trackingAt(orderId, PG_A, "TR-" + orderId, NOW.minusSeconds(100 - orderId)));
        }
        for (long orderId = 16; orderId <= 30; orderId++) {
            refundCases.put(trackingAt(orderId, PG_B, "TR-" + orderId, NOW.minusSeconds(100 - orderId)));
        }
        blockedProviders.add(PG_A);
        when(gatewayB.verifyOrder(anyString(), any())).thenReturn(
            new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));

        service.trackDueCases();

        verify(gatewayA, never()).verifyOrder(anyString(), any());
        verify(gatewayB, times(5)).verifyOrder(anyString(), any());
        for (long orderId = 1; orderId <= 15; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("막힌 PG 대상 %d의 조회 횟수", orderId).isZero();
            assertThat(refundCases.get(orderId).getNextCheckAt()).as("막힌 PG 대상 %d의 다음 조회", orderId)
                .isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        }
        for (long orderId = 16; orderId <= 20; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("조회한 대상 %d", orderId).isEqualTo(1);
        }
        for (long orderId = 21; orderId <= 30; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("상한 밖 대상 %d", orderId).isZero();
        }
    }

    @Test
    @DisplayName("조회 뒤 잠금 아래 다시 읽은 대상이 이미 환불 요청 상태로 바뀌었으면(다른 인스턴스) 취소를 보내지 않는다")
    void doesNotCancelWhenCaseChangedDuringLookup() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        when(gatewayA.verifyOrder(anyString(), any())).thenAnswer(invocation -> {
            refundCases.put(withStatus(1, PG_A, "TR-1", RefundCase.Status.REFUND_REQUESTED));
            return found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS));
        });

        service.trackDueCases();

        verify(gatewayA, never()).cancel(any());
        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.REFUND_REQUESTED);
    }

    @Test
    @DisplayName("취소 응답 전에 대상이 이미 다른 상태로 바뀌었으면 늦게 온 취소 결과를 반영하지 않고 orderId와 결과 종류만 WARN 한 줄로 남긴다")
    void logsDiscardedLateCancelResult() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        doAnswer(invocation -> {
            refundCases.put(withStatus(1, PG_A, "TR-1", RefundCase.Status.HANDED_TO_HUMAN));
            return new PaymentCancelResult.Canceled(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED));
        }).when(gatewayA).cancel(any());
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            service.trackDueCases();
        } finally {
            serviceLogger.detachAppender(logs);
        }

        assertThat(refundCases.get(1).getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(outcomes).isEmpty();
        assertThat(logs.list.stream().filter(event -> event.getFormattedMessage().contains("cancelResult=")).toList())
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
                assertThat(event.getFormattedMessage()).contains("orderId=1", "cancelResult=Canceled")
                    .doesNotContain("TR-1");
            });
    }

    private static PaymentGateway gateway(String providerId) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.providerId()).thenReturn(providerId);
        return gateway;
    }

    private void lookup(PaymentGateway gateway, long orderId, PaymentTransactionsLookup result) {
        when(gateway.verifyOrder(eq(providerOrderId(orderId)), any())).thenReturn(result);
    }

    private void cancel(PaymentGateway gateway, PaymentCancelResult result) {
        doAnswer(invocation -> {
            events.add("cancel:" + orderIdOf(invocation.getArgument(0)));
            return result;
        }).when(gateway).cancel(any());
    }

    private static long orderIdOf(PaymentCancelCommand command) {
        return Long.parseLong(command.providerOrderId().substring("LP-ORD-".length()));
    }

    private static PaymentTransactionsLookup found(PaymentTransaction transaction) {
        return new PaymentTransactionsLookup.Found(List.of(transaction), PaymentTransactionsLookup.Coverage.ALL);
    }

    private static PaymentTransaction tx(String key, long orderId, Money amount, PaymentTransactionStatus status) {
        return new PaymentTransaction(key, providerOrderId(orderId), amount, status);
    }

    private static String providerOrderId(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    private static RefundCase tracking(long orderId, String providerId, String transactionKey) {
        return trackingAt(orderId, providerId, transactionKey, OPENED_AT);
    }

    private static RefundCase trackingAt(long orderId, String providerId, String transactionKey, Instant nextCheckAt) {
        return RefundCase.restore(orderId, providerId, providerOrderId(orderId), AMOUNT, RefundCase.Status.TRACKING,
            PaymentRecord.ProviderStatus.PENDING, transactionKey, OPENED_AT, OPENED_AT.plus(RefundCase.HANDOFF_AFTER),
            OPENED_AT.plus(RefundCase.PROMISE_AFTER), nextCheckAt, 0, null, null, null);
    }

    private static RefundCase withStatus(long orderId, String providerId, String transactionKey, RefundCase.Status status) {
        Instant requestedAt = status == RefundCase.Status.TRACKING ? null : OPENED_AT.plusSeconds(30);
        return RefundCase.restore(orderId, providerId, providerOrderId(orderId), AMOUNT, status,
            PaymentRecord.ProviderStatus.PENDING, transactionKey, OPENED_AT, OPENED_AT.plus(RefundCase.HANDOFF_AFTER),
            OPENED_AT.plus(RefundCase.PROMISE_AFTER), OPENED_AT.plus(Duration.ofMinutes(1)), 0, requestedAt, null, null);
    }

    private static RefundCase copy(RefundCase source) {
        return RefundCase.restore(source.getOrderId(), source.getProviderId(), source.getProviderOrderId(), source.getAmount(),
            source.getStatus(), source.getPriorProviderStatus(), source.getTransactionKey(), source.getOpenedAt(),
            source.getHandoffAt(), source.getPromiseAt(), source.getNextCheckAt(), source.getCheckAttempts(),
            source.getRefundRequestedAt(), source.getConcludedAt(), source.getReason());
    }

    // 결론 전 상태에서 다음 조회 시각이 지난 대상을 시각 순으로 고르는 DB 조회를 흉내 낸다. 꺼낼 때·넣을 때 복사해 저장 전 변경이 새지 않게 한다.
    private static class InMemoryRefundCases implements PaymentRefundCaseRepository {
        private static final Set<RefundCase.Status> OPEN = Set.of(RefundCase.Status.TRACKING, RefundCase.Status.REFUND_REQUESTED,
            RefundCase.Status.REFUND_UNKNOWN);
        private final Map<Long, RefundCase> rows = new ConcurrentHashMap<>();
        private final List<String> events;

        InMemoryRefundCases(List<String> events) {
            this.events = events;
        }

        void put(RefundCase refundCase) {
            rows.put(refundCase.getOrderId(), copy(refundCase));
        }

        RefundCase get(long orderId) {
            return copy(rows.get(orderId));
        }

        @Override
        public RefundCase save(RefundCase refundCase) {
            events.add("save:" + refundCase.getOrderId() + ":" + refundCase.getStatus());
            rows.put(refundCase.getOrderId(), copy(refundCase));
            return copy(refundCase);
        }

        @Override
        public Optional<RefundCase> findByOrderId(long orderId) {
            return Optional.ofNullable(rows.get(orderId)).map(RefundTrackingServiceTest::copy);
        }

        @Override
        public boolean existsByOrderId(long orderId) {
            return rows.containsKey(orderId);
        }

        @Override
        public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
            return findByOrderId(orderId);
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            List<RefundCase> due = new ArrayList<>(rows.values().stream()
                .filter(row -> OPEN.contains(row.getStatus()) && row.getNextCheckAt() != null && !row.getNextCheckAt().isAfter(now))
                .toList());
            due.sort(Comparator.comparing(RefundCase::getNextCheckAt).thenComparing(RefundCase::getOrderId));
            return due.stream().limit(limit).map(RefundCase::getOrderId).toList();
        }
    }

    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final List<String> events;

        RecordingTransactions(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
