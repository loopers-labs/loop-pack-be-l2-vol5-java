package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.pgsimulator.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 공유 pgLookup 회로의 표본 분류·fallback·전이(설계 2.8 조회 분류표). 매 테스트가 새 컨텍스트의 CLOSED 회로·동시 호출 없음에서
 * 시작하고, 실제 PgHttpGateway 가 테스트 PG fixture 로 GET 을 보낸다. TX B 반영·409 응답은 범위가 아니다.
 */
@DisplayName("pgLookup 회로는 승인한 분류대로 조회 표본을 기록하고 회로 밖에서만 확인 불가로 바꾼다.")
class PgLookupCircuitTest extends PgAdapterTestSupport {

    private static final ObservationSource QUERY = ObservationSource.QUERY;
    private static final PaymentObservation UNAVAILABLE = new PaymentObservation.Unavailable(QUERY);
    private static final String ORDER = "LP-ORD-0000000600";

    @DisplayName("통신 성공으로 기록할 때,")
    @Nested
    class Success {

        @DisplayName("유효한 결제 FAILED 는 성공 1 이고 거래 관찰을 그대로 돌려준다.")
        @Test
        void validFailedPayment() {
            String key = createdKey(ORDER);
            pg.setStatus(key, PaymentStatus.FAILED);

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey(key)).isEqualTo(new PaymentObservation.TransactionObserved(
                    QUERY, key, ORDER, 7000, PgTransactionStatus.FAILED)),
                () -> assertSamples(circuit, 1, 0)
            ));
        }

        @DisplayName("계약상 거래 부재 404 와 주문별 빈 목록은 성공 1 이고 확인 불가 관찰이다.")
        @Test
        void absence() {
            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey("no-such-key")).isEqualTo(UNAVAILABLE),
                () -> assertThat(gateway.findByOrderId(ORDER)).isEqualTo(UNAVAILABLE),
                () -> assertSamples(circuit, 2, 0)
            ));
        }

        @DisplayName("요청 주문의 정확한 복수 거래는 성공 1 이고 MULTIPLE_TRANSACTIONS 관찰이다.")
        @Test
        void exactMultiple() {
            createdKey(ORDER);
            createdKey(ORDER);

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByOrderId(ORDER)).isEqualTo(new PaymentObservation.Conflict(QUERY,
                    PaymentConflictCode.MULTIPLE_TRANSACTIONS, new ConflictEvidence(null, null, null, null, 2))),
                () -> assertSamples(circuit, 1, 0)
            ));
        }

        @DisplayName("요청한 거래를 정확히 받았으면 다른 주문·금액이어도(내부 업무 충돌) 성공 1 이다.")
        @Test
        void requestedTransactionWithInternalConflict() {
            String key = createdKey("LP-ORD-0000000699");

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey(key))
                    .isInstanceOf(PaymentObservation.TransactionObserved.class),
                () -> assertSamples(circuit, 1, 0)
            ));
        }

        @DisplayName("800ms 를 넘긴 유효 응답은 성공이면서 느린 호출로 기록된다.")
        @Test
        void slowSuccess() {
            String key = createdKey(ORDER);
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.delay(900));

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey(key)).isInstanceOf(PaymentObservation.TransactionObserved.class),
                () -> assertSamples(circuit, 1, 0),
                () -> assertThat(circuit.getMetrics().getNumberOfSlowSuccessfulCalls()).isEqualTo(1)
            ));
        }
    }

    @DisplayName("직접 조회 대상 위반을 기록할 때,")
    @Nested
    class TargetViolation {

        @DisplayName("단건 조회에 다른 거래 키가 오면 실패 1 이지만 충돌 관찰을 확인 불가로 덮지 않는다.")
        @Test
        void otherTransactionKey() {
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-A", ResponsePlan.raw(200, tx("tx-B", ORDER), null));

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey("tx-A")).isEqualTo(new PaymentObservation.Conflict(QUERY,
                    PaymentConflictCode.IDENTITY_MISMATCH,
                    new ConflictEvidence(ORDER, "tx-B", 7000L, PgTransactionStatus.SUCCESS, null))),
                () -> assertSamples(circuit, 0, 1)
            ));
        }

        @DisplayName("주문별 조회에 다른 주문 거래 1건이면 실패 1·IDENTITY_MISMATCH, 섞인 복수 거래면 실패 1·MULTIPLE_TRANSACTIONS 다.")
        @Test
        void otherOrderInList() {
            String other = tx("tx-X", "LP-ORD-0000000698");
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, ORDER, ResponsePlan.raw(200, "[" + other + "]", null));
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, ORDER,
                ResponsePlan.raw(200, "[" + tx("tx-Y", ORDER) + "," + other + "]", null));

            withLookup((gateway, circuit) -> {
                PaymentObservation single = gateway.findByOrderId(ORDER);
                PaymentObservation mixed = gateway.findByOrderId(ORDER);

                assertAll(
                    () -> assertThat(single).isInstanceOfSatisfying(PaymentObservation.Conflict.class,
                        conflict -> assertThat(conflict.code()).isEqualTo(PaymentConflictCode.IDENTITY_MISMATCH)),
                    () -> assertThat(mixed).isEqualTo(new PaymentObservation.Conflict(QUERY,
                        PaymentConflictCode.MULTIPLE_TRANSACTIONS, new ConflictEvidence(null, null, null, null, 2))),
                    () -> assertSamples(circuit, 0, 2)
                );
            });
        }
    }

    @DisplayName("통신 실패로 기록할 때,")
    @Nested
    class Failure {

        @DisplayName("200 외 2xx·3xx·인증/한도/나머지 4xx·5xx 는 실패 1 이고 회로 밖에서 확인 불가가 된다.")
        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {201, 202, 204, 301, 302, 307, 308, 400, 401, 403, 405, 409, 422, 429, 500, 501, 503, 505})
        void nonContractStatus(int status) {
            boolean noBody = status == 204;
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-S",
                ResponsePlan.raw(status, noBody ? "" : tx("tx-S", ORDER), status / 100 == 3
                    ? Map.of("Location", pg.baseUrl() + "/api/v1/payments/tx-redirect") : null));

            withLookup((gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByTransactionKey("tx-S")).isEqualTo(UNAVAILABLE),
                () -> assertSamples(circuit, 0, 1),
                () -> assertThat(pg.state().counts().lookupByKeyReceived()).as("GET 1회·redirect 미추종").isEqualTo(1)
            ));
        }

        @DisplayName("해석 불가 본문·수신 중 끊김·응답 timeout 은 실패 1 이고 확인 불가다.")
        @Test
        void unusableResponses() {
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-J", ResponsePlan.raw(200, "{\"transactionKey\":", null));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-C", ResponsePlan.closeEarly());
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-T", ResponsePlan.delay(1_500));

            withLookup((gateway, circuit) -> {
                List<PaymentObservation> observations = List.of(
                    gateway.findByTransactionKey("tx-J"),
                    gateway.findByTransactionKey("tx-C"),
                    gateway.findByTransactionKey("tx-T"));
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(observations).containsOnly(UNAVAILABLE),
                    () -> assertSamples(circuit, 0, 3),
                    () -> assertThat(pg.state().counts().lookupByKeyReceived()).isEqualTo(3)
                );
            });
        }

        @DisplayName("연결 거절은 실패 1 이고 확인 불가다.")
        @Test
        void connectionRefused() throws IOException {
            withLookup("http://127.0.0.1:" + closedPort(), (gateway, circuit) -> assertAll(
                () -> assertThat(gateway.findByOrderId(ORDER)).isEqualTo(UNAVAILABLE),
                () -> assertSamples(circuit, 0, 1)
            ));
        }
    }

    @DisplayName("표본에서 제외할 때,")
    @Nested
    class Excluded {

        @DisplayName("우리 연결 풀 대기 실패는 PG 로 보내지 않고 표본도 남기지 않으며 확인 불가다.")
        @Test
        void poolAcquireTimeout() {
            IntStream.range(0, 10).forEach(i ->
                pg.enqueuePlan(PgOperation.LOOKUP_ORDER, heldOrderId(i), ResponsePlan.gate("g-" + i)));

            withLookup((gateway, circuit) -> {
                List<Future<PaymentObservation>> held = new ArrayList<>();
                IntStream.range(0, 10).forEach(i -> held.add(callers.submit(() -> gateway.findByOrderId(heldOrderId(i)))));
                await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> {
                    FixtureState state = pg.state();
                    return state.activeRequests() == 10
                        && state.gates().stream().allMatch(gate -> gate.status() == GateStatus.WAITING);
                });

                PaymentObservation observation = gateway.findByOrderId(ORDER);
                int bufferedWhileFull = circuit.getMetrics().getNumberOfBufferedCalls();
                long receivedWhileFull = pg.state().counts().lookupByOrderReceived();
                IntStream.range(0, 10).forEach(i -> pg.releaseGate("g-" + i));
                for (Future<PaymentObservation> future : held) {
                    future.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS);
                }

                assertAll(
                    () -> assertThat(observation).isEqualTo(UNAVAILABLE),
                    () -> assertThat(bufferedWhileFull).as("붙잡힌 10건은 아직 진행 중, 풀 대기 1건은 제외").isZero(),
                    () -> assertThat(receivedWhileFull).isEqualTo(10)
                );
            });
        }

        @DisplayName("정상 응답 뒤 식별한 프로그램 오류는 표본 없이 그대로 전파하고 확인 불가로 감추지 않는다.")
        @Test
        void programmingError() {
            String key = createdKey(ORDER);

            withLookup(ProgrammingErrorDecoder::new, pg.baseUrl(),
                (gateway, circuit) -> assertAll(
                    () -> assertThatThrownBy(() -> gateway.findByTransactionKey(key))
                        .isInstanceOf(PgAdapterProgrammingException.class),
                    () -> assertSamples(circuit, 0, 0),
                    () -> assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero()
                ));
        }
    }

    @DisplayName("회로가 열리고 닫힐 때,")
    @Nested
    class Transition {

        @DisplayName("실패 5건 중 마지막이 직접 대상 위반이어도 충돌 관찰을 돌려주고, 열린 뒤 조회는 PG 로 보내지 않는다.")
        @Test
        void opensAndBlocksWithoutGet() {
            IntStream.range(0, 4).forEach(i ->
                pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-F" + i, ResponsePlan.raw(503, "", null)));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-A", ResponsePlan.raw(200, tx("tx-B", ORDER), null));

            withLookup((gateway, circuit) -> {
                IntStream.range(0, 4).forEach(i -> gateway.findByTransactionKey("tx-F" + i));
                PaymentObservation fifth = gateway.findByTransactionKey("tx-A");
                CircuitBreaker.State afterFifth = circuit.getState();
                long receivedBeforeBlocked = pg.state().counts().lookupByKeyReceived()
                    + pg.state().counts().lookupByOrderReceived();

                PaymentObservation blockedByKey = gateway.findByTransactionKey("tx-G");
                PaymentObservation blockedByOrder = gateway.findByOrderId(ORDER);
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(fifth).isInstanceOfSatisfying(PaymentObservation.Conflict.class,
                        conflict -> assertThat(conflict.code()).isEqualTo(PaymentConflictCode.IDENTITY_MISMATCH)),
                    () -> assertThat(afterFifth).isEqualTo(CircuitBreaker.State.OPEN),
                    () -> assertThat(List.of(blockedByKey, blockedByOrder)).containsOnly(UNAVAILABLE),
                    () -> assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(2),
                    () -> assertThat(pg.state().counts().lookupByKeyReceived() + pg.state().counts().lookupByOrderReceived())
                        .as("OPEN 중 GET 0").isEqualTo(receivedBeforeBlocked)
                );
            });
        }

        @DisplayName("거래 키 조회와 주문별 조회는 같은 회로를 쓰고 pgCreate 와는 분리된다.")
        @Test
        void sharesLookupCircuitOnly() {
            withLookupRegistry((gateway, registry) -> {
                IntStream.range(0, 5).forEach(i -> gateway.findByOrderId("LP-ORD-00000006" + (10 + i)));
                pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000620", ResponsePlan.raw(500, "", null));
                IntStream.range(0, 5).forEach(i ->
                    pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, "tx-H" + i, ResponsePlan.raw(500, "", null)));
                IntStream.range(0, 5).forEach(i -> gateway.findByTransactionKey("tx-H" + i));

                CircuitBreaker lookup = registry.circuitBreaker("pgLookup");
                assertAll(
                    () -> assertThat(lookup.getMetrics().getNumberOfSuccessfulCalls()).as("주문별 성공 5").isEqualTo(5),
                    () -> assertThat(lookup.getMetrics().getNumberOfFailedCalls()).as("거래 키 실패 5").isEqualTo(5),
                    () -> assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.OPEN),
                    () -> assertThat(registry.circuitBreaker("pgCreate").getMetrics().getNumberOfBufferedCalls()).isZero(),
                    () -> assertThat(registry.circuitBreaker("pgCreate").getState()).isEqualTo(CircuitBreaker.State.CLOSED)
                );
            });
        }

        @DisplayName("HALF_OPEN 의 탐색 2건이 성공하면 CLOSED 로 돌아와 다시 조회한다.")
        @Test
        void recoversAfterSuccessfulProbes() {
            String key = createdKey(ORDER);

            withLookup((gateway, circuit) -> {
                circuit.transitionToOpenState();
                circuit.transitionToHalfOpenState();

                PaymentObservation first = gateway.findByTransactionKey(key);
                PaymentObservation second = gateway.findByTransactionKey(key);

                assertAll(
                    () -> assertThat(List.of(first, second)).allSatisfy(observation ->
                        assertThat(observation).isInstanceOf(PaymentObservation.TransactionObserved.class)),
                    () -> assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
                );
            });
        }
    }

    private void withLookup(LookupTest test) {
        withLookup(PgResponseDecoder::new, pg.baseUrl(), test);
    }

    private void withLookup(String baseUrl, LookupTest test) {
        withLookup(PgResponseDecoder::new, baseUrl, test);
    }

    private void withLookup(Supplier<PgResponseDecoder> decoder, String baseUrl, LookupTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgHttpGateway.class)
            .withBean(PgResponseDecoder.class, decoder)
            .withPropertyValues("commerce.pg.base-url=" + baseUrl)
            .run(context -> {
                assertThat(context).hasNotFailed();
                CircuitBreaker circuit = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("pgLookup");
                assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                test.run(context.getBean(PaymentGateway.class), circuit);
            });
    }

    private void withLookupRegistry(RegistryTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class, PgHttpGateway.class)
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> test.run(context.getBean(PaymentGateway.class), context.getBean(CircuitBreakerRegistry.class)));
    }

    /** 표본 수만 대조한다. 집계·상태는 이 테스트의 회로 하나만 보며 다른 테스트와 공유하지 않는다. */
    private static void assertSamples(CircuitBreaker circuit, int successful, int failed) {
        CircuitBreaker.Metrics metrics = circuit.getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).as("성공 표본").isEqualTo(successful);
        assertThat(metrics.getNumberOfFailedCalls()).as("실패 표본").isEqualTo(failed);
    }

    /** 테스트 준비용 거래를 fixture 에 직접 만든다. 회로 표본과 무관하다. */
    private String createdKey(String orderId) {
        pg.enqueuePlan(PgOperation.CREATE, orderId, ResponsePlan.normal());
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class, PgHttpGateway.class)
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> context.getBean(PaymentGateway.class)
                .create(new PaymentRequest(orderId, 7000, "TEST", "0000-0000-0000-0000")));
        awaitHandlersFinished();
        List<String> keys = pg.state().transactions().stream()
            .filter(transaction -> transaction.orderId().equals(orderId))
            .map(transaction -> transaction.transactionKey())
            .toList();
        return keys.get(keys.size() - 1);
    }

    private static String tx(String key, String orderId) {
        return "{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + orderId + "\",\"amount\":7000,\"status\":\"SUCCESS\"}";
    }

    private static String heldOrderId(int index) {
        return "LP-ORD-00000006" + (40 + index);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    /** 정상 응답 검증 뒤 내부 로직에서 프로그램 오류가 난 상황을 만든다. */
    static class ProgrammingErrorDecoder extends PgResponseDecoder {

        @Override
        PgLookupResult decodeLookupByKey(PgHttpResponse response, String requestedKey) {
            PgLookupResult decoded = super.decodeLookupByKey(response, requestedKey);
            throw new PgAdapterProgrammingException("검증 뒤 내부 오류 주입: " + decoded.directLookupTargetMismatch(),
                new NullPointerException());
        }
    }

    @FunctionalInterface
    private interface LookupTest {
        void run(PaymentGateway gateway, CircuitBreaker circuit) throws Exception;
    }

    @FunctionalInterface
    private interface RegistryTest {
        void run(PaymentGateway gateway, CircuitBreakerRegistry registry) throws Exception;
    }
}
