package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.fixture.pg.CallbackBatchResult;
import com.loopers.fixture.pg.CallbackDelivery;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.PaymentStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.fixture.pg.TransactionSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * PgHttpGateway 의 실제 HTTP 계약(4.5 PgHttpGatewayContractTest 일부). 실제 RestClient+Apache client 로
 * 테스트 PG fixture 와 통신해 요청 형태와 관찰 변환을 본다. 회로 표본 집계는 PgLookupCircuitTest, TX B 반영은 범위가 아니다.
 */
@DisplayName("PgHttpGateway 는 PG HTTP 계약대로 요청하고 응답을 관찰로 바꾼다.")
class PgHttpGatewayContractTest extends PgAdapterTestSupport {

    private static final String CALLBACK_PATH = "/api/v1/payments/callback";

    private HttpServer callbackReceiver;
    private ExecutorService receiverWorkers;
    private final AtomicInteger callbacksReceived = new AtomicInteger();

    @BeforeEach
    void startCallbackReceiver() throws IOException {
        receiverWorkers = Executors.newFixedThreadPool(2);
        callbackReceiver = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        callbackReceiver.createContext(CALLBACK_PATH, exchange -> {
            callbacksReceived.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        callbackReceiver.setExecutor(receiverWorkers);
        callbackReceiver.start();
    }

    @AfterEach
    void stopCallbackReceiver() throws InterruptedException {
        try {
            callbackReceiver.stop(0);
        } finally {
            receiverWorkers.shutdownNow();
            assertThat(receiverWorkers.awaitTermination(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("수신 대역 종료").isTrue();
        }
    }

    @DisplayName("결제를 생성할 때,")
    @Nested
    class Create {

        @DisplayName("POST 한 번으로 주문·금액·합성 카드·설정 callbackUrl 을 보내고 202 PENDING 을 CREATE 거래 관찰로 돌려준다.")
        @Test
        void sendsContractRequest() {
            withGateway(gateway -> {
                PaymentObservation observation = gateway.create(request("LP-ORD-0000000500", 7000));
                awaitHandlersFinished();
                TransactionSnapshot stored = pg.state().transactions().get(0);
                CallbackBatchResult callback = pg.sendCallbacks(stored.transactionKey(), 1)
                    .toCompletableFuture().get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS);

                assertAll(
                    () -> assertThat(observation).isEqualTo(new PaymentObservation.TransactionObserved(
                        ObservationSource.CREATE, stored.transactionKey(), "LP-ORD-0000000500", 7000,
                        PgTransactionStatus.PENDING)),
                    () -> assertThat(stored.orderId()).isEqualTo("LP-ORD-0000000500"),
                    () -> assertThat(stored.amount()).isEqualTo(7000),
                    () -> assertThat(pg.state().counts().createReceived()).isEqualTo(1),
                    () -> assertThat(callback.deliveries()).extracting(CallbackDelivery::httpStatus)
                        .as("PG 가 저장한 callbackUrl 이 설정값").containsExactly(200),
                    () -> assertThat(callbacksReceived.get()).isEqualTo(1)
                );
            });
        }

        @DisplayName("즉시 SUCCESS 는 거래 관찰, 정확한 422 는 효과 0건 거절 관찰이다.")
        @Test
        void mapsSuccessAndRejection() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000501", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000502",
                ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));

            withGateway(gateway -> {
                PaymentObservation success = gateway.create(request("LP-ORD-0000000501", 7000));
                PaymentObservation rejected = gateway.create(request("LP-ORD-0000000502", 8000));
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(success).isInstanceOfSatisfying(PaymentObservation.TransactionObserved.class,
                        observed -> assertThat(observed.status()).isEqualTo(PgTransactionStatus.SUCCESS)),
                    () -> assertThat(rejected).isEqualTo(new PaymentObservation.RejectedNoEffect("LP-ORD-0000000502", 8000)),
                    () -> assertThat(pg.state().transactions()).extracting(TransactionSnapshot::orderId)
                        .containsExactly("LP-ORD-0000000501")
                );
            });
        }

        @DisplayName("응답 주문·금액이 요청과 다르면 CREATE 충돌 근거를 보존한다.")
        @Test
        void mapsMismatchedResponse() {
            String body = "{\"transactionKey\":\"tx-raw\",\"orderId\":\"LP-ORD-0000000503\",\"amount\":1,\"status\":\"PENDING\"}";
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000503", ResponsePlan.raw(202, body, null));

            withGateway(gateway -> assertThat(gateway.create(request("LP-ORD-0000000503", 7000))).isEqualTo(
                new PaymentObservation.Conflict(ObservationSource.CREATE, PaymentConflictCode.AMOUNT_MISMATCH,
                    new ConflictEvidence("LP-ORD-0000000503", "tx-raw", 1L, PgTransactionStatus.PENDING, null))));
        }

        @DisplayName("5xx·수신 중 끊김·응답 timeout 은 확인 불가이며 PG 가 거래를 만들었어도 다시 보내지 않는다.")
        @Test
        void mapsFailuresWithoutResend() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000504", ResponsePlan.raw(500, "{}", null));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000505", ResponsePlan.closeEarly());
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000506", ResponsePlan.delay(1_500));

            withGateway(gateway -> {
                List<PaymentObservation> observations = List.of(
                    gateway.create(request("LP-ORD-0000000504", 7000)),
                    gateway.create(request("LP-ORD-0000000505", 7000)),
                    gateway.create(request("LP-ORD-0000000506", 7000)));
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(observations).containsOnly(new PaymentObservation.Unavailable(ObservationSource.CREATE)),
                    () -> assertThat(pg.state().counts().createReceived()).as("주문별 POST 1회").isEqualTo(3),
                    () -> assertThat(pg.state().transactions()).as("응답을 잃어도 PG 거래는 존재").hasSize(3)
                );
            });
        }
    }

    @DisplayName("거래 키로 조회할 때,")
    @Nested
    class LookupByKey {

        @DisplayName("존재 거래는 QUERY 거래 관찰, 없는 거래 404 는 확인 불가다.")
        @Test
        void foundAndNotFound() {
            withGateway(gateway -> {
                gateway.create(request("LP-ORD-0000000510", 7000));
                awaitHandlersFinished();
                String key = pg.state().transactions().get(0).transactionKey();
                pg.setStatus(key, PaymentStatus.SUCCESS);

                PaymentObservation found = gateway.findByTransactionKey(key);
                PaymentObservation missing = gateway.findByTransactionKey("no-such-key");
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(found).isEqualTo(new PaymentObservation.TransactionObserved(
                        ObservationSource.QUERY, key, "LP-ORD-0000000510", 7000, PgTransactionStatus.SUCCESS)),
                    () -> assertThat(missing).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(pg.state().journal()).extracting(JournalEntry::operation, JournalEntry::target)
                        .as("실제 단건 조회 경로로 요청").endsWith(
                            tuple(PgOperation.LOOKUP_TRANSACTION, key),
                            tuple(PgOperation.LOOKUP_TRANSACTION, "no-such-key"))
                );
            });
        }

        @DisplayName("다른 거래 키를 돌려주면 IDENTITY_MISMATCH, redirect·끊김은 확인 불가이고 GET 은 한 번이다.")
        @Test
        void mapsViolations() {
            String forged = "{\"transactionKey\":\"tx-B\",\"orderId\":\"LP-ORD-0000000511\",\"amount\":7000,\"status\":\"SUCCESS\"}";
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-A", ResponsePlan.raw(200, forged, null));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-C", ResponsePlan.raw(302, "",
                Map.of("Location", pg.baseUrl() + "/api/v1/payments/tx-redirected")));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-D", ResponsePlan.closeEarly());

            withGateway(gateway -> {
                PaymentObservation otherKey = gateway.findByTransactionKey("tx-A");
                PaymentObservation redirected = gateway.findByTransactionKey("tx-C");
                PaymentObservation broken = gateway.findByTransactionKey("tx-D");
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(otherKey).isEqualTo(new PaymentObservation.Conflict(ObservationSource.QUERY,
                        PaymentConflictCode.IDENTITY_MISMATCH,
                        new ConflictEvidence("LP-ORD-0000000511", "tx-B", 7000L, PgTransactionStatus.SUCCESS, null))),
                    () -> assertThat(redirected).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(broken).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(pg.state().journal()).extracting(JournalEntry::target)
                        .as("redirect 대상 수신 없음·재시도 없음").containsExactly("tx-A", "tx-C", "tx-D")
                );
            });
        }
    }

    @DisplayName("주문별로 조회할 때,")
    @Nested
    class LookupByOrder {

        @DisplayName("빈 목록은 확인 불가, 같은 주문의 복수 거래는 MULTIPLE_TRANSACTIONS 다.")
        @Test
        void emptyAndMultiple() {
            withGateway(gateway -> {
                PaymentObservation none = gateway.findByOrderId("LP-ORD-0000000520");
                gateway.create(request("LP-ORD-0000000521", 7000));
                gateway.create(request("LP-ORD-0000000521", 7000));
                PaymentObservation multiple = gateway.findByOrderId("LP-ORD-0000000521");

                assertAll(
                    () -> assertThat(none).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(multiple).isEqualTo(new PaymentObservation.Conflict(ObservationSource.QUERY,
                        PaymentConflictCode.MULTIPLE_TRANSACTIONS, new ConflictEvidence(null, null, null, null, 2)))
                );
            });
        }

        @DisplayName("예약 문자가 든 주문 ID 도 생성·조회에 같은 문자열로 전달한다.")
        @Test
        void encodesOrderId() {
            String orderId = "LP-ORD 1&x=2+3#가?/%";

            withGateway(gateway -> {
                PaymentObservation created = gateway.create(request(orderId, 7000));
                PaymentObservation found = gateway.findByOrderId(orderId);
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(created).isInstanceOf(PaymentObservation.TransactionObserved.class),
                    () -> assertThat(found).isInstanceOfSatisfying(PaymentObservation.TransactionObserved.class,
                        observed -> assertThat(observed.providerOrderId()).isEqualTo(orderId)),
                    () -> assertThat(pg.state().journal()).extracting(JournalEntry::target).containsExactly(orderId, orderId)
                );
            });
        }

        @DisplayName("응답 timeout 은 확인 불가이고 GET 은 한 번이다.")
        @Test
        void mapsReadTimeout() {
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000522", ResponsePlan.delay(1_500));

            withGateway(gateway -> {
                PaymentObservation observation = gateway.findByOrderId("LP-ORD-0000000522");
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(observation).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1)
                );
            });
        }

        @DisplayName("풀의 연결 10개가 모두 쓰이면 PG 로 보내지 않고 확인 불가다.")
        @Test
        void mapsPoolAcquireTimeout() {
            IntStream.range(0, 10).forEach(i ->
                pg.enqueuePlan(PgOperation.LOOKUP_ORDER, heldOrderId(i), ResponsePlan.gate("g-" + i)));

            withGateway(gateway -> {
                List<Future<PaymentObservation>> held = new ArrayList<>();
                IntStream.range(0, 10).forEach(i -> held.add(callers.submit(() -> gateway.findByOrderId(heldOrderId(i)))));
                await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> {
                    FixtureState state = pg.state();
                    return state.activeRequests() == 10
                        && state.gates().stream().allMatch(gate -> gate.status() == GateStatus.WAITING);
                });

                PaymentObservation observation = gateway.findByOrderId("LP-ORD-0000000599");
                long receivedWhileFull = pg.state().counts().lookupByOrderReceived();
                IntStream.range(0, 10).forEach(i -> pg.releaseGate("g-" + i));
                for (Future<PaymentObservation> future : held) {
                    future.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS);
                }

                assertAll(
                    () -> assertThat(observation).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(receivedWhileFull).as("11번째 조회 PG 수신 0").isEqualTo(10)
                );
            });
        }
    }

    @DisplayName("생성 요청의 문자열 표현에는 카드 값이 없다.")
    @Test
    void hidesCardInRequestText() {
        assertThat(request("LP-ORD-0000000530", 7000).toString()).doesNotContain("0000-0000-0000-0000").doesNotContain("TEST");
    }

    private void withGateway(ThrowingConsumer<PaymentGateway> test) {
        PgHttpConfigTest.RUNNER
            .withUserConfiguration(PgResponseDecoder.class, PgHttpGateway.class)
            .withPropertyValues(
                "commerce.pg.base-url=" + pg.baseUrl(),
                "commerce.pg.callback-url=http://127.0.0.1:" + callbackReceiver.getAddress().getPort() + CALLBACK_PATH)
            .run(context -> {
                assertThat(context).hasNotFailed();
                test.accept(context.getBean(PaymentGateway.class));
            });
    }

    private static PaymentRequest request(String providerOrderId, long amount) {
        return new PaymentRequest(providerOrderId, amount, "TEST", "0000-0000-0000-0000");
    }

    private static String heldOrderId(int index) {
        return "LP-ORD-00000005" + (40 + index);
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
