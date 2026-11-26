package com.loopers.domains.payment.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.payment.app.port.LookupJitter;
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
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.domain.RefundCase.RefundActor;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 포트만 fake/mock으로 두고 환불 추적 한 회차의 판단과 경계를 본다(10주차 5단계 계획 2-1·2-2).
// 트랜잭션 관리자는 commit·rollback을 사건 목록에 남겨, 환불 요청 상태 저장이 commit된 뒤에 취소가 나가는지 순서로 확인한다.
// jitter는 상한의 절반을 고르는 고정 대역이다. 다음 조회 시각을 정확한 값으로 보고, 상한을 그대로 쓰는 변이(jitter 제거)를 잡는다(S10).
class RefundTrackingServiceTest {
    private static final Instant OPENED_AT = Instant.parse("2026-10-21T00:02:00Z");
    private static final Instant NOW = OPENED_AT.plus(Duration.ofMinutes(5));
    private static final String PG_A = "pg-a";
    private static final String PG_B = "pg-b";
    private static final Money AMOUNT = new Money(2000);
    private static final Duration HALF_MINUTE = Duration.ofSeconds(30);
    private static final Duration HALF_PERSON = Duration.ofMinutes(15);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Set<String> blockedProviders = new HashSet<>();
    private final List<String> outcomes = new CopyOnWriteArrayList<>();
    private final List<RefundCase.CheckOutcome> checks = new CopyOnWriteArrayList<>();
    private final HalfJitter jitter = new HalfJitter();
    private InMemoryRefundCases refundCases;
    private PaymentGateway gatewayA;
    private PaymentGateway gatewayB;
    private MutablePaymentClock clock;
    private RefundCheckFailureRecorder failureRecorder;
    private RefundTrackingService service;

    @BeforeEach
    void prepare() {
        refundCases = new InMemoryRefundCases(events);
        gatewayA = gateway(PG_A);
        gatewayB = gateway(PG_B);
        clock = new MutablePaymentClock();
        clock.set(NOW);
        failureRecorder = mock(RefundCheckFailureRecorder.class);
        service = new RefundTrackingService(refundCases, new PaymentGatewayRegistry(List.of(gatewayA, gatewayB), List.of(PG_A, PG_B)),
            blockedProviders::contains, new TransactionTemplate(new RecordingTransactions(events)), clock,
            new RecordingOutcomes(), jitter, failureRecorder);
    }

    @Test
    @DisplayName("확인 중 대상의 조회가 금액·주문·키가 맞는 단일 SUCCESS면 환불 요청함을 저장·commit한 뒤에 취소를 한 번 보내고 취소 확인으로 환불됨(SYSTEM)이 된다")
    void requestsRefundAfterCommitAndConcludes() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Canceled(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        assertThat(events).containsSubsequence("save:1:REFUND_REQUESTED", "commit", "cancel:1", "save:1:REFUNDED", "commit",
            "outcome:REFUNDED/SYSTEM");
        assertThat(events.indexOf("cancel:1")).isGreaterThan(events.indexOf("save:1:REFUND_REQUESTED") + 1);
        verify(gatewayA, times(1)).cancel(new PaymentCancelCommand(providerOrderId(1), "TR-1", AMOUNT));
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCases.get(1).getRefundActor()).isEqualTo(RefundActor.SYSTEM);
        assertThat(refundCases.get(1).getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCases.get(1).getRefundRequestedAt()).isEqualTo(NOW);
        assertThat(outcomes).containsExactly("REFUNDED/SYSTEM");
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
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
    }

    @Test
    @DisplayName("취소 결과를 모르면 환불 결과 모름으로 두고 결과 확인 상한 1분 안(고정 대역 30초 뒤)에 조회하며 결론 counter를 세지 않는다")
    void marksUnknownCancel() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Unknown("timeout"));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
        assertThat(refundCases.get(1).getNextCheckAt()).isEqualTo(NOW.plus(HALF_MINUTE));
        verify(gatewayA, times(1)).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    // [판단 필요] NotSent도 요청 전 기록이 이미 commit된 뒤라 다시 보내지 않고 결과 모름처럼 조회로만 확인한다(안전한 기본값).
    @Test
    @DisplayName("취소가 PG에 닿지 않았다고 분류돼도 환불 요청함이 이미 commit됐으므로 다시 보내지 않고 환불 결과 모름으로 둔다")
    void treatsNotSentCancelAsUnknown() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.NotSent("connect refused"));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
        verify(gatewayA, times(1)).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @Test
    @DisplayName("PG가 효과 없이 취소를 거절하면 자동으로 더 하지 않고 돈은 청구 확인·담당만 사람(REFUND_REJECTED)으로 두며 사람 담당 이관을 사유별로 센다")
    void assignsPersonOnRejectedCancel() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.RejectedNoEffect("TEST_PG_CANCEL_REJECTED"));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.REFUND_REJECTED);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(NOW);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(NOW.plus(HALF_PERSON));
        verify(gatewayA, times(1)).cancel(any());
        assertThat(outcomes).containsExactly("assigned:" + RefundCase.REFUND_REJECTED);
    }

    @Test
    @DisplayName("취소 호출이 예외로 끝나면 다시 보내지 않고 환불 결과 모름으로 두며 같은 회차의 다음 대상은 계속 처리한다")
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

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
        assertThat(events).containsSubsequence("save:1:REFUND_REQUESTED", "commit", "cancel:1", "save:1:REFUND_UNKNOWN", "commit");
        assertThat(events.stream().filter("cancel:1"::equals)).hasSize(1);
        assertThat(refundCases.get(2).getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(outcomes).containsExactly("NO_CHARGE/SYSTEM");
    }

    @Test
    @DisplayName("확인 중 대상의 조회가 일치하는 단일 FAILED면 취소 없이 청구 없음으로 닫고 청구 없음(SYSTEM)을 센다")
    void closesWhenNoCharge() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.FAILED)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(refundCases.get(1).getRefundActor()).isNull();
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("NO_CHARGE/SYSTEM");
    }

    @Test
    @DisplayName("S4: 확인 중 대상의 조회가 일치하는 단일 CANCELED면 청구 없음이 아니라 환불됨·주체 EXTERNAL로 끝나고 취소를 보내지 않으며 환불됨(EXTERNAL)을 센다")
    void concludesExternalRefundWhileChecking() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCases.get(1).getRefundActor()).isEqualTo(RefundActor.EXTERNAL);
        assertThat(refundCases.get(1).getConcludedAt()).isEqualTo(NOW);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("REFUNDED/EXTERNAL");
    }

    @ParameterizedTest(name = "조회 결과={0}")
    @ValueSource(strings = {"pending", "zero", "failed", "exception"})
    @DisplayName("PENDING·0건·조회 실패·조회 예외는 결론 근거가 아니라 확인 중을 유지하고 조회 횟수를 1 늘려 상한 1분 안(고정 대역 30초 뒤)에 다시 본다")
    void keepsCheckingWhenInconclusive(String result) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        switch (result) {
            case "pending" -> lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.PENDING)));
            case "zero" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));
            case "failed" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Failed("500"));
            default -> when(gatewayA.verifyOrder(anyString(), any())).thenThrow(new IllegalStateException("주입된 조회 예외"));
        }

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(NOW.plus(HALF_MINUTE));
        assertThat(jitter.maxima).containsExactly(Duration.ofMinutes(1).toMillis());
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @ParameterizedTest(name = "조회 전 횟수={0} → 상한 {1}분")
    @CsvSource({"1, 2", "5, 32", "6, 60", "40, 60"})
    @DisplayName("S10: 결론 없는 조회가 이어지면 jitter에 넘기는 상한이 1분에서 두 배씩 1시간까지 늘고 다음 조회는 그 상한의 고정 대역 값(절반) 뒤다")
    void passesBackoffCeilingToJitter(int attemptsBefore, long ceilingMinutes) {
        refundCases.put(RefundCase.restore(1, PG_A, providerOrderId(1), AMOUNT, MoneyStatus.CHECKING, null, Owner.AUTO, null,
            null, null, null, PaymentRecord.ProviderStatus.PENDING, "TR-1", OPENED_AT, OPENED_AT.plus(RefundCase.HANDOFF_AFTER),
            OPENED_AT.plus(RefundCase.PROMISE_AFTER), OPENED_AT, attemptsBefore, null, null));
        lookup(gatewayA, 1, new PaymentTransactionsLookup.Failed("500"));

        service.trackDueCases();

        assertThat(jitter.maxima).containsExactly(Duration.ofMinutes(ceilingMinutes).toMillis());
        assertThat(refundCases.get(1).getNextCheckAt()).isEqualTo(NOW.plus(Duration.ofMinutes(ceilingMinutes).dividedBy(2)));
    }

    @Test
    @DisplayName("게이트웨이가 회로 정책으로 PG를 부르지 않으면 조회 횟수는 그대로 두고 마지막 상한 1분 안(고정 대역 30초 뒤)으로 다음 조회만 미룬다")
    void postponesWhenLookupNotCalled() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, new PaymentTransactionsLookup.NotCalled());

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCases.get(1).getCheckAttempts()).isZero();
        assertThat(refundCases.get(1).getNextCheckAt()).isEqualTo(NOW.plus(HALF_MINUTE));
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"amount", "order", "key", "multiple-all", "multiple-single"})
    @DisplayName("S2: 확인 중 대상에 금액·주문·키가 다른 단일 거래나 복수 거래가 보이면 취소하지 않고 청구 확인·담당만 사람(사유)으로 두며 사람 담당 이관을 사유별로 센다")
    void assignsPersonOnMismatchWhileChecking(String scene) {
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

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(expectedReason);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getNextCheckAt()).isEqualTo(NOW.plus(HALF_PERSON));
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("assigned:" + expectedReason);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("S2: 환불 요청 뒤 대상에 다른 거래·복수 거래가 보이면 돈의 상태는 그대로 두고 담당만 즉시 사람(사유)으로 바꾸며 취소를 보내지 않는다")
    void assignsPersonOnMismatchAfterRequestKeepingMoney(MoneyStatus moneyStatus) {
        refundCases.put(withStatus(1, PG_A, "TR-1", moneyStatus));
        lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS),
            tx("TR-2", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)), PaymentTransactionsLookup.Coverage.ALL));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(OPENED_AT.plusSeconds(30));
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.MULTIPLE_TRANSACTIONS);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("assigned:" + RefundCase.MULTIPLE_TRANSACTIONS);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 대상은 조회만 하고 CANCELED가 보이면 취소를 다시 보내지 않고 환불됨(SYSTEM)으로 결론 낸다")
    void concludesRefundByLookupOnly(MoneyStatus moneyStatus) {
        refundCases.put(withStatus(1, PG_A, "TR-1", moneyStatus));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCases.get(1).getRefundActor()).isEqualTo(RefundActor.SYSTEM);
        verify(gatewayA).verifyOrder(providerOrderId(1), "TR-1");
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("REFUNDED/SYSTEM");
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("환불 요청 뒤 대상이 아직 SUCCESS로 보이면 취소를 다시 보내지 않고 돈의 상태를 유지하며 조회 횟수만 늘린다")
    void neverResendsRefund(MoneyStatus moneyStatus) {
        refundCases.put(withStatus(1, PG_A, "TR-1", moneyStatus));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));

        service.trackDueCases();

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCases.get(1).getCheckAttempts()).isEqualTo(1);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHECKING", "REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("S2: 이관 기한이 지난 자동 담당 대상은 조회하지 않고 돈의 상태는 그대로 두며 담당만 사람(HANDOFF_DEADLINE)으로 넘기고 사람 담당 이관을 사유별로 센다")
    void assignsPersonAtDeadlineKeepingMoney(MoneyStatus moneyStatus) {
        refundCases.put(withStatus(1, PG_A, "TR-1", moneyStatus));
        Instant deadline = OPENED_AT.plus(Duration.ofDays(2));
        clock.set(deadline);

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(deadline);
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(deadline.plus(HALF_PERSON));
        verify(gatewayA, never()).verifyOrder(anyString(), any());
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("assigned:" + RefundCase.HANDOFF_DEADLINE);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHECKING", "CHARGED"})
    @DisplayName("S3: 사람 담당 중인 대상도 조회를 계속하고 SUCCESS가 보여도 취소를 보내지 않으며 청구 확인으로 기록하고 상한 30분 안(고정 대역 15분 뒤)에 다시 본다")
    void keepsLookingUpForPersonWithoutCancel(MoneyStatus moneyStatus) {
        refundCases.put(withState(1, PG_A, "TR-1", moneyStatus, Owner.PERSON, OPENED_AT));
        clock.set(OPENED_AT.plus(Duration.ofDays(3)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        verify(gatewayA, times(1)).verifyOrder(providerOrderId(1), "TR-1");
        verify(gatewayA, never()).cancel(any());
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getRefundRequestedAt()).isNull();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(OPENED_AT.plus(Duration.ofDays(3)).plus(HALF_PERSON));
        assertThat(jitter.maxima).containsExactly(Duration.ofMinutes(30).toMillis());
        assertThat(outcomes).isEmpty();
    }

    @ParameterizedTest(name = "돈의 상태={0} → 환불 주체 {1}")
    @CsvSource({"CHECKING, EXTERNAL", "CHARGED, EXTERNAL", "REFUND_REQUESTED, SYSTEM"})
    @DisplayName("S3: 사람 담당 중인 대상에 PG가 CANCELED를 주면 환불됨으로 끝나고 담당을 SYSTEM이 자동 종료하며 취소는 보내지 않는다")
    void closesPersonOwnerOnCanceled(MoneyStatus moneyStatus, RefundActor expectedActor) {
        refundCases.put(withState(1, PG_A, "TR-1", moneyStatus, Owner.PERSON, OPENED_AT));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getRefundActor()).isEqualTo(expectedActor);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(NOW);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(refundCase.isPersonOpen()).isFalse();
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).containsExactly("REFUNDED/" + expectedActor);
    }

    @Test
    @DisplayName("S3: 사람 담당 중인 확인 중 대상에 PG가 단일 FAILED를 주면 청구 없음으로 끝나고 담당을 SYSTEM이 자동 종료한다")
    void closesPersonOwnerOnFailedWhileChecking() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHECKING, Owner.PERSON, OPENED_AT));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.FAILED)));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(NOW);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(outcomes).containsExactly("NO_CHARGE/SYSTEM");
    }

    @Test
    @DisplayName("S3: 청구 확인·사람 담당 대상에 PG가 단일 FAILED를 주면(사실이 엇갈림) 돈은 청구 확인 그대로이고 담당도 사람으로 유지하며 다시 본다")
    void keepsPersonOwnerOnFailedAfterCharged() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, OPENED_AT));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.FAILED)));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getOwnerClosedBy()).isNull();
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
        assertThat(refundCase.getNextCheckAt()).isEqualTo(NOW.plus(HALF_PERSON));
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(RefundCase.CheckOutcome.MISMATCH);
        assertThat(outcomes).isEmpty();
    }

    @Test
    @DisplayName("S3: 사람 담당 중인 확인 중 대상에 다른 거래가 보이면 청구 확인으로 기록하되 담당은 처음 사유 그대로이고 다시 세지 않는다")
    void recordsChargedForPersonOnMismatch() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHECKING, Owner.PERSON, OPENED_AT));
        lookup(gatewayA, 1, found(tx("TR-1", 1, new Money(2001), PaymentTransactionStatus.SUCCESS)));

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        verify(gatewayA, never()).cancel(any());
        assertThat(outcomes).isEmpty();
    }

    @Test
    @DisplayName("S3: 사람 담당 중인 대상은 이관 기한이 지나도 다시 넘기지 않고 조회한다")
    void looksUpPersonCaseAfterDeadline() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.REFUND_REQUESTED, Owner.PERSON, OPENED_AT));
        clock.set(OPENED_AT.plus(Duration.ofDays(3)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));

        service.trackDueCases();

        verify(gatewayA, times(1)).verifyOrder(providerOrderId(1), "TR-1");
        assertThat(refundCases.get(1).getOwnerChangedAt()).isEqualTo(OPENED_AT.plusSeconds(40));
        assertThat(outcomes).isEmpty();
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
                .isEqualTo(NOW.plus(HALF_MINUTE));
        }
        for (long orderId = 16; orderId <= 20; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("조회한 대상 %d", orderId).isEqualTo(1);
        }
        for (long orderId = 21; orderId <= 30; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("상한 밖 대상 %d", orderId).isZero();
        }
    }

    @Test
    @DisplayName("S3: 회차 상한 20건은 자동·사람 담당 합계이고 자동 담당을 먼저 고른다")
    void prefersAutoWithinSharedBatchLimit() {
        for (long orderId = 1; orderId <= 15; orderId++) {
            refundCases.put(withState(orderId, PG_A, "TR-" + orderId, MoneyStatus.CHARGED, Owner.PERSON,
                NOW.minusSeconds(200 - orderId)));
        }
        for (long orderId = 16; orderId <= 30; orderId++) {
            refundCases.put(trackingAt(orderId, PG_A, "TR-" + orderId, NOW.minusSeconds(100 - orderId)));
        }
        when(gatewayA.verifyOrder(anyString(), any())).thenReturn(
            new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));

        service.trackDueCases();

        verify(gatewayA, times(RefundTrackingService.BATCH_SIZE)).verifyOrder(anyString(), any());
        for (long orderId = 16; orderId <= 30; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("자동 담당 %d", orderId).isEqualTo(1);
        }
        for (long orderId = 1; orderId <= 5; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("시각이 이른 사람 담당 %d", orderId).isEqualTo(1);
        }
        for (long orderId = 6; orderId <= 15; orderId++) {
            assertThat(refundCases.get(orderId).getCheckAttempts()).as("상한 밖 사람 담당 %d", orderId).isZero();
        }
    }

    @Test
    @DisplayName("조회 뒤 잠금 아래 다시 읽은 대상이 이미 환불 요청함으로 바뀌었으면(다른 인스턴스) 취소를 보내지 않는다")
    void doesNotCancelWhenCaseChangedDuringLookup() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        when(gatewayA.verifyOrder(anyString(), any())).thenAnswer(invocation -> {
            refundCases.put(withStatus(1, PG_A, "TR-1", MoneyStatus.REFUND_REQUESTED));
            return found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS));
        });

        service.trackDueCases();

        verify(gatewayA, never()).cancel(any());
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
    }

    @Test
    @DisplayName("조회 뒤 잠금 아래 다시 읽은 확인 중 대상의 담당이 사람으로 바뀌었으면 SUCCESS가 보여도 취소를 보내지 않고 조회 결과도 반영하지 않는다")
    void doesNotCancelWhenOwnerChangedDuringLookup() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        when(gatewayA.verifyOrder(anyString(), any())).thenAnswer(invocation -> {
            refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHECKING, Owner.PERSON, OPENED_AT));
            return found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS));
        });

        service.trackDueCases();

        verify(gatewayA, never()).cancel(any());
        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getRefundRequestedAt()).isNull();
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getCheckAttempts()).isZero();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(OPENED_AT);
    }

    @Test
    @DisplayName("R3: 조회하는 동안 이관 기한이 지난 자동 담당 확인 중 대상은 SUCCESS가 보여도 담당을 먼저 사람(HANDOFF_DEADLINE)으로 바꾼 뒤 청구 확인으로 기록해 취소를 보내지 않고 이관은 commit 뒤 한 번 센다")
    void assignsPersonBeforeApplyingLookupWhenDeadlinePassesDuringLookup() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        Instant deadline = OPENED_AT.plus(RefundCase.HANDOFF_AFTER);
        when(gatewayA.verifyOrder(anyString(), any())).thenAnswer(invocation -> {
            clock.set(deadline);
            return found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS));
        });

        service.trackDueCases();

        verify(gatewayA, never()).cancel(any());
        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getTransactionKey()).isEqualTo("TR-1");
        assertThat(refundCase.getRefundRequestedAt()).isNull();
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(deadline);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getNextCheckAt()).isEqualTo(deadline.plus(HALF_PERSON));
        assertThat(outcomes).containsExactly("assigned:" + RefundCase.HANDOFF_DEADLINE);
        assertThat(events).containsSubsequence("save:1:CHARGED", "commit", "assigned:" + RefundCase.HANDOFF_DEADLINE);
    }

    @Test
    @DisplayName("취소 응답 전에 대상이 이미 다른 상태로 바뀌었으면 늦게 온 취소 결과를 반영하지 않고 orderId와 결과 종류만 WARN 한 줄로 남긴다")
    void logsDiscardedLateCancelResult() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        doAnswer(invocation -> {
            refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.REFUND_UNKNOWN, Owner.AUTO, NOW));
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

        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
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

    @ParameterizedTest(name = "조회 장면={0} → 마지막 확인={1}, 원인={2}")
    @CsvSource({
        "unreachable, PG_UNREACHABLE, ResourceAccessException(SocketTimeoutException)",
        "exception, PG_UNREACHABLE, IllegalStateException",
        "not-called, PG_UNREACHABLE, ",
        "error-response, PG_ERROR, HTTP 500",
        "zero, EMPTY, ",
        "pending, PENDING, ",
        "mismatch, MISMATCH, ",
        "multiple, MISMATCH, ",
        "failed, OK, ",
        "canceled, OK, "
    })
    @DisplayName("C2: 회차의 조회 결과마다 마지막 확인 시각·분류·원인(클래스 이름·짧은 사유만, 예외 메시지 없음)을 대상에 남기고 그 분류를 commit 뒤 한 번 센다")
    void recordsLastCheckPerLookupResult(String scene, RefundCase.CheckOutcome expected, String expectedCause) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        switch (scene) {
            case "unreachable" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Failed(
                "ResourceAccessException(SocketTimeoutException)", PaymentTransactionsLookup.FailureKind.UNREACHABLE));
            case "exception" -> when(gatewayA.verifyOrder(anyString(), any()))
                .thenThrow(new IllegalStateException("PG-SECRET 내부 메시지"));
            case "not-called" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.NotCalled());
            case "error-response" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Failed("HTTP 500",
                PaymentTransactionsLookup.FailureKind.ERROR_RESPONSE));
            case "zero" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(), PaymentTransactionsLookup.Coverage.ALL));
            case "pending" -> lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.PENDING)));
            case "mismatch" -> lookup(gatewayA, 1, found(tx("TR-1", 1, new Money(1), PaymentTransactionStatus.SUCCESS)));
            case "multiple" -> lookup(gatewayA, 1, new PaymentTransactionsLookup.Found(List.of(
                tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS), tx("TR-2", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)),
                PaymentTransactionsLookup.Coverage.ALL));
            case "failed" -> lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.FAILED)));
            default -> lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));
        }

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getLastCheckAt()).isEqualTo(NOW);
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(expected);
        assertThat(refundCase.getLastCheckCause()).isEqualTo(expectedCause);
        assertThat(checks).containsExactly(expected);
    }

    @Test
    @DisplayName("C2: 회로가 막힌 PG의 대상은 PG를 부르지 않고 마지막 확인을 PG_UNREACHABLE로 남기며 돈·담당·조회 횟수는 그대로다")
    void recordsUnreachableWhenCircuitBlocksLookup() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        blockedProviders.add(PG_A);

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        verify(gatewayA, never()).verifyOrder(anyString(), any());
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getCheckAttempts()).isZero();
        assertThat(refundCase.getLastCheckAt()).isEqualTo(NOW);
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(RefundCase.CheckOutcome.PG_UNREACHABLE);
        assertThat(checks).containsExactly(RefundCase.CheckOutcome.PG_UNREACHABLE);
    }

    @ParameterizedTest(name = "취소 결과={0} → 마지막 확인={1}, 원인={2}, 돈의 상태={3}")
    @CsvSource({
        "canceled, OK, , REFUNDED",
        "rejected, CANCEL_REJECTED, , CHARGED",
        "unknown, CANCEL_UNKNOWN, SocketTimeoutException, REFUND_UNKNOWN",
        "not-sent, CANCEL_UNKNOWN, ConnectException, REFUND_UNKNOWN",
        "throws, CANCEL_UNKNOWN, IllegalStateException, REFUND_UNKNOWN"
    })
    @DisplayName("C2: 환불 요청 회차는 취소 결과로 마지막 확인을 남기고(결과 모름은 우리 코드가 만든 원인과 함께, 거절은 PG 거절 코드를 넣지 않고) 한 회차에 한 번만 센다")
    void recordsLastCheckPerCancelResult(String scene, RefundCase.CheckOutcome expected, String expectedCause,
        MoneyStatus expectedMoney) {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        switch (scene) {
            case "canceled" -> cancel(gatewayA,
                new PaymentCancelResult.Canceled(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));
            case "rejected" -> cancel(gatewayA, new PaymentCancelResult.RejectedNoEffect("ALREADY_SETTLED"));
            case "unknown" -> cancel(gatewayA, new PaymentCancelResult.Unknown("SocketTimeoutException"));
            case "not-sent" -> cancel(gatewayA, new PaymentCancelResult.NotSent("ConnectException"));
            default -> when(gatewayA.cancel(any())).thenThrow(new IllegalStateException("PG-SECRET 내부 메시지"));
        }

        service.trackDueCases();

        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(expectedMoney);
        assertThat(refundCase.getLastCheckAt()).isEqualTo(NOW);
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(expected);
        assertThat(refundCase.getLastCheckCause()).isEqualTo(expectedCause);
        assertThat(checks).containsExactly(expected);
    }

    @Test
    @DisplayName("C2: 조회 결과 반영이 예외로 끝나면 대상은 그대로이고 취소를 보내지 않으며 반영 실패 기록기에 주문과 예외 클래스 이름(메시지 없음)만 넘긴다")
    void handsApplyFailureToRecorder() {
        refundCases.put(tracking(1, PG_A, "TR-1"));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        refundCases.failNextSaveOf(MoneyStatus.REFUND_REQUESTED, new IllegalStateException("PG-SECRET 내부 메시지"));

        service.trackDueCases();

        verify(failureRecorder, times(1)).recordApplyFailed(1, "IllegalStateException");
        verify(gatewayA, never()).cancel(any());
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(checks).isEmpty();
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"CHECKING", "CHARGED"})
    @DisplayName("C3: 사람 담당 중 대상에 지금 처리를 누르면 다음 조회 시각 전이어도 바로 조회하고 SUCCESS면 환불 요청함 commit 뒤 취소를 한 번 보내 환불됨(SYSTEM)으로 끝나며 담당은 누른 관리자 이름으로 닫힌다")
    void processNowRefundsPersonCase(MoneyStatus moneyStatus) {
        refundCases.put(withState(1, PG_A, "TR-1", moneyStatus, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Canceled(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.CANCELED)));

        RefundTrackingService.ProcessNowResult result = service.processNow(1, "ops-admin");

        assertThat(events).containsSubsequence("save:1:REFUND_REQUESTED", "commit", "cancel:1", "save:1:REFUNDED", "commit");
        verify(gatewayA, times(1)).cancel(new PaymentCancelCommand(providerOrderId(1), "TR-1", AMOUNT));
        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getRefundActor()).isEqualTo(RefundActor.SYSTEM);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerClosedAt()).isEqualTo(NOW);
        assertThat(refundCase.getOwnerClosedBy()).isEqualTo("ops-admin");
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(RefundCase.CheckOutcome.OK);
        assertThat(result).isEqualTo(new RefundTrackingService.ProcessNowResult(1, "REFUNDED", "SYSTEM", "PERSON", NOW,
            "ops-admin", NOW, "OK", null));
        assertThat(outcomes).containsExactly("REFUNDED/SYSTEM");
        assertThat(checks).containsExactly(RefundCase.CheckOutcome.OK);
    }

    @ParameterizedTest(name = "돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUND_REQUESTED", "REFUND_UNKNOWN"})
    @DisplayName("C4: 환불을 요청한 뒤인 사람 담당 대상에 지금 처리를 누르면 조회만 하고 SUCCESS가 보여도 취소를 다시 보내지 않으며 담당은 열린 채다")
    void processNowOnlyLooksUpAfterRefundRequested(MoneyStatus moneyStatus) {
        refundCases.put(withState(1, PG_A, "TR-1", moneyStatus, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));

        RefundTrackingService.ProcessNowResult result = service.processNow(1, "ops-admin");

        verify(gatewayA, times(1)).verifyOrder(providerOrderId(1), "TR-1");
        verify(gatewayA, never()).cancel(any());
        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(moneyStatus);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getLastCheckAt()).isEqualTo(NOW);
        assertThat(result.moneyStatus()).isEqualTo(moneyStatus.name());
        assertThat(result.owner()).isEqualTo("PERSON");
        assertThat(result.ownerClosedAt()).isNull();
    }

    @Test
    @DisplayName("C5: 지금 처리를 두 번 연속 누르면 첫 번째에 보낸 취소의 결과를 모를 때 두 번째는 조회만 해 취소 호출은 1번이다")
    void processNowTwiceSendsCancelOnce() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Unknown("SocketTimeoutException"));

        RefundTrackingService.ProcessNowResult first = service.processNow(1, "ops-admin");
        RefundTrackingService.ProcessNowResult second = service.processNow(1, "ops-admin");

        verify(gatewayA, times(2)).verifyOrder(providerOrderId(1), "TR-1");
        verify(gatewayA, times(1)).cancel(any());
        assertThat(first.moneyStatus()).isEqualTo("REFUND_UNKNOWN");
        assertThat(first.lastCheckOutcome()).isEqualTo("CANCEL_UNKNOWN");
        assertThat(second.moneyStatus()).isEqualTo("REFUND_UNKNOWN");
        assertThat(refundCases.get(1).isPersonOpen()).isTrue();
    }

    @Test
    @DisplayName("C6: 회로가 막힌 PG의 사람 담당 대상에 지금 처리를 누르면 PG를 부르지 않고 PG_UNREACHABLE을 돌려주며 돈·담당은 그대로다")
    void processNowSkipsPgWhenCircuitBlocks() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        blockedProviders.add(PG_A);

        RefundTrackingService.ProcessNowResult result = service.processNow(1, "ops-admin");

        verify(gatewayA, never()).verifyOrder(anyString(), any());
        verify(gatewayA, never()).cancel(any());
        RefundCase refundCase = refundCases.get(1);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getLastCheckOutcome()).isEqualTo(RefundCase.CheckOutcome.PG_UNREACHABLE);
        assertThat(result.moneyStatus()).isEqualTo("CHARGED");
        assertThat(result.owner()).isEqualTo("PERSON");
        assertThat(result.ownerClosedAt()).isNull();
        assertThat(result.lastCheckOutcome()).isEqualTo("PG_UNREACHABLE");
    }

    @Test
    @DisplayName("C1: 반영 실패 원인 기록마저 예외로 끝나면 원래 반영 예외를 그대로 다시 던지고 기록 실패는 예외 클래스 이름만 ERROR 한 줄로 남긴다")
    void rethrowsOriginalWhenFailureRecordFails() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        refundCases.failNextSaveOf(MoneyStatus.REFUND_REQUESTED, new IllegalStateException("PG-SECRET 반영 메시지"));
        doThrow(new IllegalArgumentException("RECORD-SECRET 기록 메시지")).when(failureRecorder)
            .recordApplyFailed(anyLong(), anyString());
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            assertThatThrownBy(() -> service.processNow(1, "ops-admin"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("PG-SECRET 반영 메시지");
        } finally {
            serviceLogger.detachAppender(logs);
        }

        verify(failureRecorder, times(1)).recordApplyFailed(1, "IllegalStateException");
        verify(gatewayA, never()).cancel(any());
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList())
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
                assertThat(event.getFormattedMessage()).contains("orderId=1", "causeType=IllegalArgumentException")
                    .doesNotContain("SECRET");
            });
    }

    // PG가 첫 요청이 효과 없었다고 명시한 경우라 다시 보내도 이중 환불이 아니다(참모 20:42 확정 ①).
    @Test
    @DisplayName("[참모 20:42 확정 ①] 사람 담당 청구 확인 대상에 지금 처리를 눌러 PG가 취소를 효과 없이 거절하면 청구 확인·사람 담당 그대로 CANCEL_REJECTED로 남고, 다시 누르면 취소를 한 번 더 보낸다")
    void processNowAgainAfterRejectedCancelSendsCancelAgain() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.RejectedNoEffect("ALREADY_SETTLED"));

        RefundTrackingService.ProcessNowResult first = service.processNow(1, "ops-admin");

        RefundCase rejected = refundCases.get(1);
        assertThat(rejected.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(rejected.isPersonOpen()).isTrue();
        assertThat(rejected.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(rejected.getRefundRequestedAt()).isEqualTo(NOW);
        assertThat(rejected.getLastCheckOutcome()).isEqualTo(RefundCase.CheckOutcome.CANCEL_REJECTED);
        assertThat(rejected.getLastCheckCause()).isNull();
        assertThat(first.moneyStatus()).isEqualTo("CHARGED");
        assertThat(first.owner()).isEqualTo("PERSON");
        assertThat(first.lastCheckOutcome()).isEqualTo("CANCEL_REJECTED");
        verify(gatewayA, times(1)).cancel(any());

        Instant later = NOW.plus(Duration.ofMinutes(3));
        clock.set(later);
        service.processNow(1, "ops-admin");

        verify(gatewayA, times(2)).cancel(new PaymentCancelCommand(providerOrderId(1), "TR-1", AMOUNT));
        assertThat(events.stream().filter("cancel:1"::equals)).hasSize(2);
        assertThat(refundCases.get(1).getRefundRequestedAt()).isEqualTo(later);
        assertThat(refundCases.get(1).getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(outcomes).isEmpty();
        assertThat(checks).containsExactly(RefundCase.CheckOutcome.CANCEL_REJECTED, RefundCase.CheckOutcome.CANCEL_REJECTED);
    }

    @Test
    @DisplayName("사람 담당 대상에 지금 처리를 두 번 누르면 누를 때마다 orderId·관리자 이름·마지막 확인 분류가 담긴 INFO가 한 줄씩 남고 거래 키는 남지 않는다")
    void processNowLogsOneInfoPerPress() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        cancel(gatewayA, new PaymentCancelResult.Unknown("SocketTimeoutException"));
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        SnapshotAppender logs = new SnapshotAppender();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            service.processNow(1, "ops-admin");
            service.processNow(1, "ops-admin");
        } finally {
            serviceLogger.detachAppender(logs);
        }

        List<ILoggingEvent> presses = pressLogs(logs);
        assertThat(presses).hasSize(2);
        assertThat(presses.get(0).getFormattedMessage()).contains("orderId=1", "admin=ops-admin", "outcome=CANCEL_UNKNOWN");
        assertThat(presses.get(1).getFormattedMessage()).contains("orderId=1", "admin=ops-admin", "outcome=OK");
        assertThat(presses).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
            assertThat(event.getFormattedMessage()).doesNotContain("TR-1", "SocketTimeoutException");
            assertThat(event.getMDCPropertyMap().values()).noneMatch(value -> value.contains("TR-1"));
        });
    }

    @Test
    @DisplayName("지금 처리가 반영 실패로 끝나면 outcome=APPLY_FAILED인 INFO 한 줄을 남기고 원래 예외를 그대로 다시 던지며 예외 메시지·거래 키는 남기지 않는다")
    void processNowLogsApplyFailedPress() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        lookup(gatewayA, 1, found(tx("TR-1", 1, AMOUNT, PaymentTransactionStatus.SUCCESS)));
        refundCases.failNextSaveOf(MoneyStatus.REFUND_REQUESTED, new IllegalStateException("PG-SECRET 반영 메시지"));
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        SnapshotAppender logs = new SnapshotAppender();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            assertThatThrownBy(() -> service.processNow(1, "ops-admin"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("PG-SECRET 반영 메시지");
        } finally {
            serviceLogger.detachAppender(logs);
        }

        assertThat(pressLogs(logs))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
                assertThat(event.getFormattedMessage()).contains("orderId=1", "admin=ops-admin", "outcome=APPLY_FAILED")
                    .doesNotContain("SECRET", "TR-1");
            });
    }

    @ParameterizedTest(name = "대상={0}, 거절={1}")
    @CsvSource({
        "REFUNDED_PERSON, CONCLUDED",
        "CHARGED_AUTO, NOT_PERSON",
        "NONE, NOT_FOUND"
    })
    @DisplayName("지금 처리가 거절되면(돈 끝남·사람 담당 아님·대상 없음) PG를 부르지 않고 거절 사유가 담긴 INFO 한 줄을 남긴 뒤 원래 예외를 던진다")
    void processNowLogsRejectedPress(String target, String rejected) {
        if (target.equals("REFUNDED_PERSON")) {
            refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.REFUNDED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        } else if (target.equals("CHARGED_AUTO")) {
            refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.AUTO, NOW.plus(HALF_PERSON)));
        }
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        SnapshotAppender logs = new SnapshotAppender();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            assertThatThrownBy(() -> service.processNow(1, "ops-admin"))
                .isInstanceOf(rejected.equals("NOT_FOUND") ? RefundCaseNotFoundException.class : RuleViolationException.class);
        } finally {
            serviceLogger.detachAppender(logs);
        }

        verify(gatewayA, never()).verifyOrder(anyString(), any());
        assertThat(pressLogs(logs))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
                assertThat(event.getFormattedMessage()).contains("orderId=1", "admin=ops-admin", "rejected=" + rejected)
                    .doesNotContain("outcome=", "TR-1");
            });
    }

    @Test
    @DisplayName("지금 처리가 대상 잠금을 얻지 못하면 APPLY_FAILED로 섞지 않고 rejected=BUSY인 INFO 한 줄을 남긴 뒤 원래 예외를 던지며 반영 실패 원인은 기록하지 않는다")
    void processNowLogsBusyPress() {
        refundCases.put(withState(1, PG_A, "TR-1", MoneyStatus.CHARGED, Owner.PERSON, NOW.plus(HALF_PERSON)));
        PessimisticLockingFailureException lockFailure = new PessimisticLockingFailureException("LOCK-SECRET 잠금 메시지");
        refundCases.failNextLock(lockFailure);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
        SnapshotAppender logs = new SnapshotAppender();
        logs.start();
        serviceLogger.addAppender(logs);

        try {
            assertThatThrownBy(() -> service.processNow(1, "ops-admin")).isSameAs(lockFailure);
        } finally {
            serviceLogger.detachAppender(logs);
        }

        verify(gatewayA, never()).verifyOrder(anyString(), any());
        verify(failureRecorder, never()).recordApplyFailed(anyLong(), anyString());
        assertThat(pressLogs(logs))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", "1");
                assertThat(event.getFormattedMessage()).contains("orderId=1", "admin=ops-admin", "rejected=BUSY")
                    .doesNotContain("outcome=", "SECRET", "TR-1");
            });
    }

    private static List<ILoggingEvent> pressLogs(SnapshotAppender logs) {
        return logs.list.stream().filter(event -> event.getFormattedMessage().startsWith("지금 처리를 눌렀습니다.")).toList();
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
        return RefundCase.restore(orderId, providerId, providerOrderId(orderId), AMOUNT, MoneyStatus.CHECKING, null, Owner.AUTO,
            null, null, null, null, PaymentRecord.ProviderStatus.PENDING, transactionKey, OPENED_AT,
            OPENED_AT.plus(RefundCase.HANDOFF_AFTER), OPENED_AT.plus(RefundCase.PROMISE_AFTER), nextCheckAt, 0, null, null);
    }

    private static RefundCase withStatus(long orderId, String providerId, String transactionKey, MoneyStatus moneyStatus) {
        return withState(orderId, providerId, transactionKey, moneyStatus, Owner.AUTO, OPENED_AT.plus(Duration.ofMinutes(1)));
    }

    // 사람 담당은 기한 이관(HANDOFF_DEADLINE)으로 열린 채로 둔다. 환불을 요청한 적 있는 돈의 상태만 요청 시각을 남긴다.
    private static RefundCase withState(long orderId, String providerId, String transactionKey, MoneyStatus moneyStatus,
        Owner owner, Instant nextCheckAt) {
        Instant requestedAt = moneyStatus == MoneyStatus.REFUND_REQUESTED || moneyStatus == MoneyStatus.REFUND_UNKNOWN
            ? OPENED_AT.plusSeconds(30) : null;
        boolean person = owner == Owner.PERSON;
        return RefundCase.restore(orderId, providerId, providerOrderId(orderId), AMOUNT, moneyStatus, null, owner,
            person ? RefundCase.HANDOFF_DEADLINE : null, person ? OPENED_AT.plusSeconds(40) : null, null, null,
            PaymentRecord.ProviderStatus.PENDING, transactionKey, OPENED_AT, OPENED_AT.plus(RefundCase.HANDOFF_AFTER),
            OPENED_AT.plus(RefundCase.PROMISE_AFTER), nextCheckAt, 0, requestedAt, null);
    }

    private static RefundCase copy(RefundCase source) {
        return RefundCase.restore(source.getOrderId(), source.getProviderId(), source.getProviderOrderId(), source.getAmount(),
            source.getMoneyStatus(), source.getRefundActor(), source.getOwner(), source.getOwnerReason(),
            source.getOwnerChangedAt(), source.getOwnerClosedAt(), source.getOwnerClosedBy(), source.getPriorProviderStatus(),
            source.getTransactionKey(), source.getOpenedAt(), source.getHandoffAt(), source.getPromiseAt(),
            source.getNextCheckAt(), source.getCheckAttempts(), source.getRefundRequestedAt(), source.getConcludedAt(),
            source.getLastCheckAt(), source.getLastCheckOutcome(), source.getLastCheckCause());
    }

    // 돈의 상태가 끝나지 않은 대상 중 다음 조회 시각이 지난 것을 담당과 무관하게 고르되 자동 담당을 먼저, 그다음 시각 순으로 고르는 DB 조회를
    // 흉내 낸다(계획 3절). 꺼낼 때·넣을 때 복사해 저장 전 변경이 새지 않게 한다.
    private static class InMemoryRefundCases implements PaymentRefundCaseRepository {
        private static final Set<MoneyStatus> OPEN = Set.of(MoneyStatus.CHECKING, MoneyStatus.CHARGED,
            MoneyStatus.REFUND_REQUESTED, MoneyStatus.REFUND_UNKNOWN);
        private final Map<Long, RefundCase> rows = new ConcurrentHashMap<>();
        private final List<String> events;
        private RuntimeException saveFailure;
        private MoneyStatus failingMoneyStatus;
        private RuntimeException lockFailure;

        InMemoryRefundCases(List<String> events) {
            this.events = events;
        }

        void put(RefundCase refundCase) {
            rows.put(refundCase.getOrderId(), copy(refundCase));
        }

        RefundCase get(long orderId) {
            return copy(rows.get(orderId));
        }

        void failNextSaveOf(MoneyStatus moneyStatus, RuntimeException failure) {
            saveFailure = failure;
            failingMoneyStatus = moneyStatus;
        }

        @Override
        public RefundCase save(RefundCase refundCase) {
            if (saveFailure != null && refundCase.getMoneyStatus() == failingMoneyStatus) {
                RuntimeException failure = saveFailure;
                saveFailure = null;
                throw failure;
            }
            events.add("save:" + refundCase.getOrderId() + ":" + refundCase.getMoneyStatus());
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

        void failNextLock(RuntimeException failure) {
            lockFailure = failure;
        }

        @Override
        public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
            if (lockFailure != null) {
                RuntimeException failure = lockFailure;
                lockFailure = null;
                throw failure;
            }
            return findByOrderId(orderId);
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            List<RefundCase> due = new ArrayList<>(rows.values().stream()
                .filter(row -> OPEN.contains(row.getMoneyStatus()) && row.getNextCheckAt() != null
                    && !row.getNextCheckAt().isAfter(now))
                .toList());
            due.sort(Comparator.comparing(RefundCase::getOwner).thenComparing(RefundCase::getNextCheckAt)
                .thenComparing(RefundCase::getOrderId));
            return due.stream().limit(limit).map(RefundCase::getOrderId).toList();
        }

        @Override
        public void recordCheck(long orderId, RefundCase.CheckOutcome outcome, String cause, Instant checkedAt) {
            throw new UnsupportedOperationException("반영 실패 기록은 RefundCheckFailureRecorder 대역으로 본다.");
        }
    }

    // 상한의 절반을 고르고 요청받은 상한을 남긴다.
    private static class HalfJitter implements LookupJitter {
        private final List<Long> maxima = new CopyOnWriteArrayList<>();

        @Override
        public long chooseMillis(long maximumMillis) {
            maxima.add(maximumMillis);
            return maximumMillis / 2;
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

    // 결론은 "돈의 상태/주체", 사람 담당은 "assigned:사유"로 남긴다. commit 순서도 보도록 events에도 남긴다.
    private class RecordingOutcomes implements RefundOutcomeRecorder {
        @Override
        public void recordOutcome(Result result, RefundActor actor) {
            events.add("outcome:" + result + "/" + actor);
            outcomes.add(result + "/" + actor);
        }

        @Override
        public void recordPersonAssigned(String reason) {
            events.add("assigned:" + reason);
            outcomes.add("assigned:" + reason);
        }

        @Override
        public void recordCheck(RefundCase.CheckOutcome outcome) {
            checks.add(outcome);
        }
    }

    // 이벤트의 MDC는 처음 읽을 때 현재 스레드에서 가져오므로, 기록 시점의 값을 남기려고 받는 즉시 고정한다.
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
