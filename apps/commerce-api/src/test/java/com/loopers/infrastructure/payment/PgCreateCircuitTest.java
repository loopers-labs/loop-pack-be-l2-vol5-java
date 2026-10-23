package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.CreateAdmission;
import com.loopers.domain.payment.LocalNotDispatchedReason;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * pgCreate 사전 확인·생성 decorator·미발송 판정(설계 2.8, 4.5 생성 회로·미발송 판정의 Gateway 수준). 매 테스트가 새 컨텍스트의
 * CLOSED 회로에서 시작하고 실제 PgHttpGateway 가 테스트 PG fixture 로 POST 한다.
 * confirm 응답·TX A/B·재고 반환은 범위가 아니며 I3·I4 에서 같은 사례로 다시 확인한다.
 */
@DisplayName("pgCreate 회로는 사전 확인·실제 권한·미발송 판정을 분리하고 승인한 분류대로 생성 표본을 기록한다.")
class PgCreateCircuitTest extends PgAdapterTestSupport {

    private static final PaymentObservation CREATE_UNAVAILABLE = new PaymentObservation.Unavailable(ObservationSource.CREATE);

    @DisplayName("사전 확인할 때,")
    @Nested
    class Admission {

        @DisplayName("CLOSED 면 READY, OPEN 이면 BLOCKED 이고 HTTP 와 성공·실패 표본이 없다.")
        @Test
        void readyOrBlocked() {
            withCreate((gateway, create, lookup) -> {
                CreateAdmission closed = gateway.checkCreateAdmission();
                create.transitionToOpenState();
                CreateAdmission open = gateway.checkCreateAdmission();

                assertAll(
                    () -> assertThat(closed).isEqualTo(CreateAdmission.READY),
                    () -> assertThat(open).isEqualTo(CreateAdmission.BLOCKED),
                    () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero(),
                    () -> assertThat(create.getMetrics().getNumberOfNotPermittedCalls()).as("거절 계수만 별도").isEqualTo(1),
                    () -> assertThat(pg.state().counts().createReceived()).isZero()
                );
            });
        }

        @DisplayName("HALF_OPEN 에서 여러 번 사전 확인해도 탐색 슬롯을 돌려줘 실제 생성 2건이 모두 POST 한다.")
        @Test
        void releasesHalfOpenSlot() {
            withCreate((gateway, create, lookup) -> {
                create.transitionToOpenState();
                create.transitionToHalfOpenState();
                List<CreateAdmission> admissions = IntStream.range(0, 3).mapToObj(i -> gateway.checkCreateAdmission()).toList();

                PaymentObservation first = gateway.create(request("LP-ORD-0000000700"));
                PaymentObservation second = gateway.create(request("LP-ORD-0000000701"));

                assertAll(
                    () -> assertThat(admissions).containsOnly(CreateAdmission.READY),
                    () -> assertThat(List.of(first, second)).allSatisfy(observation ->
                        assertThat(observation).isInstanceOf(PaymentObservation.TransactionObserved.class)),
                    () -> assertThat(pg.state().counts().createReceived()).isEqualTo(2),
                    () -> assertThat(create.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
                );
            });
        }
    }

    @DisplayName("생성 표본을 분류할 때,")
    @Nested
    class Classification {

        @DisplayName("202 PENDING·200 SUCCESS·정확한 422 는 성공 1 이다.")
        @Test
        void contractResponses() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000711", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000712", ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));

            withCreate((gateway, create, lookup) -> {
                PaymentObservation pending = gateway.create(request("LP-ORD-0000000710"));
                PaymentObservation success = gateway.create(request("LP-ORD-0000000711"));
                PaymentObservation rejected = gateway.create(request("LP-ORD-0000000712"));

                assertAll(
                    () -> assertThat(pending).isInstanceOf(PaymentObservation.TransactionObserved.class),
                    () -> assertThat(success).isInstanceOf(PaymentObservation.TransactionObserved.class),
                    () -> assertThat(rejected).isEqualTo(new PaymentObservation.RejectedNoEffect("LP-ORD-0000000712", 7000)),
                    () -> assertSamples(create, 3, 0)
                );
            });
        }

        @DisplayName("응답 주문·금액이 요청과 다르면 실패 1 이지만 CREATE 충돌 근거를 보존한다.")
        @Test
        void mismatchedResponse() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000713", ResponsePlan.raw(202,
                "{\"transactionKey\":\"tx-m\",\"orderId\":\"LP-ORD-0000000713\",\"amount\":1,\"status\":\"PENDING\"}", null));

            withCreate((gateway, create, lookup) -> assertAll(
                () -> assertThat(gateway.create(request("LP-ORD-0000000713"))).isEqualTo(new PaymentObservation.Conflict(
                    ObservationSource.CREATE, PaymentConflictCode.AMOUNT_MISMATCH,
                    new ConflictEvidence("LP-ORD-0000000713", "tx-m", 1L, PgTransactionStatus.PENDING, null))),
                () -> assertSamples(create, 0, 1)
            ));
        }

        @DisplayName("계약 외 HTTP 상태는 실패 1 이고 확인 불가이며 POST 는 1회다.")
        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {201, 302, 400, 409, 422, 429, 500, 503})
        void nonContractStatus(int status) {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000714", ResponsePlan.raw(status, "{}", null));

            withCreate((gateway, create, lookup) -> assertAll(
                () -> assertThat(gateway.create(request("LP-ORD-0000000714"))).isEqualTo(CREATE_UNAVAILABLE),
                () -> assertSamples(create, 0, 1),
                () -> assertThat(pg.state().counts().createReceived()).isEqualTo(1)
            ));
        }

        @DisplayName("해석 불가·수신 중 끊김·응답 timeout·연결 거절은 실패이고 확인 불가이며 미발송으로 판정하지 않는다.")
        @Test
        void communicationFailures() throws IOException {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000715", ResponsePlan.raw(202, "{", null));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000716", ResponsePlan.closeEarly());
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000717", ResponsePlan.delay(1_500));

            withCreate((gateway, create, lookup) -> {
                List<PaymentObservation> observations = List.of(
                    gateway.create(request("LP-ORD-0000000715")),
                    gateway.create(request("LP-ORD-0000000716")),
                    gateway.create(request("LP-ORD-0000000717")));
                awaitHandlersFinished();

                assertAll(
                    () -> assertThat(observations).containsOnly(CREATE_UNAVAILABLE),
                    () -> assertSamples(create, 0, 3),
                    () -> assertThat(pg.state().counts().createReceived()).isEqualTo(3)
                );
            });
            withCreate("http://127.0.0.1:" + closedPort(), (gateway, create, lookup) -> assertAll(
                () -> assertThat(gateway.create(request("LP-ORD-0000000718"))).isEqualTo(CREATE_UNAVAILABLE),
                () -> assertSamples(create, 0, 1)
            ));
        }
    }

    @DisplayName("미발송을 판정할 때,")
    @Nested
    class NotDispatched {

        @DisplayName("사전 확인 통과 뒤 실제 권한이 거절되면 본문 미진입·POST 0 의 CIRCUIT_REJECTED 이고 표본이 없다.")
        @Test
        void circuitRejectedAfterAdmission() {
            withCreate((gateway, create, lookup) -> {
                CreateAdmission admission = gateway.checkCreateAdmission();
                create.transitionToOpenState();

                PaymentObservation observation = gateway.create(request("LP-ORD-0000000720"));

                assertAll(
                    () -> assertThat(admission).isEqualTo(CreateAdmission.READY),
                    () -> assertThat(observation).isEqualTo(
                        new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED)),
                    () -> assertThat(pg.state().counts().createReceived()).isZero(),
                    () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero(),
                    () -> assertThat(create.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1)
                );
            });
        }

        @DisplayName("HALF_OPEN 탐색 2건이 진행 중이면 세 번째는 PG 수신 없이 CIRCUIT_REJECTED 다.")
        @Test
        void circuitRejectedWhenProbesExhausted() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000721", ResponsePlan.gate("g-721"));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000722", ResponsePlan.gate("g-722"));

            withCreate((gateway, create, lookup) -> {
                create.transitionToOpenState();
                create.transitionToHalfOpenState();
                Future<PaymentObservation> first = callers.submit(() -> gateway.create(request("LP-ORD-0000000721")));
                Future<PaymentObservation> second = callers.submit(() -> gateway.create(request("LP-ORD-0000000722")));
                awaitGatesWaiting(2);

                PaymentObservation third = gateway.create(request("LP-ORD-0000000723"));
                long receivedWhileProbing = pg.state().counts().createReceived();
                pg.releaseGate("g-721");
                pg.releaseGate("g-722");

                assertAll(
                    () -> assertThat(third).isEqualTo(
                        new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED)),
                    () -> assertThat(receivedWhileProbing).isEqualTo(2),
                    () -> assertThat(first.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                        .isInstanceOf(PaymentObservation.TransactionObserved.class),
                    () -> assertThat(second.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                        .isInstanceOf(PaymentObservation.TransactionObserved.class)
                );
            });
        }

        @DisplayName("진입 표시는 호출마다 따로라서, 진행 중인 첫 호출은 OPEN 뒤에도 끝나고 두 번째만 CIRCUIT_REJECTED 다.")
        @Test
        void entryMarkIsPerCall() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000724", ResponsePlan.gate("g-724"));

            withCreate((gateway, create, lookup) -> {
                Future<PaymentObservation> first = callers.submit(() -> gateway.create(request("LP-ORD-0000000724")));
                awaitGatesWaiting(1);
                create.transitionToOpenState();

                PaymentObservation second = gateway.create(request("LP-ORD-0000000725"));
                pg.releaseGate("g-724");

                assertAll(
                    () -> assertThat(second).isEqualTo(
                        new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED)),
                    () -> assertThat(first.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS))
                        .as("이미 권한을 얻은 호출은 진행").isInstanceOf(PaymentObservation.TransactionObserved.class),
                    () -> assertThat(pg.state().counts().createReceived()).isEqualTo(1)
                );
            });
        }

        @DisplayName("이 POST 의 최초 풀 획득이 실패하면 PG 수신 0·표본 0 의 POOL_ACQUIRE_TIMEOUT 이고, 같은 상황의 조회는 확인 불가다.")
        @Test
        void poolAcquireTimeout() {
            IntStream.range(0, 10).forEach(i ->
                pg.enqueuePlan(PgOperation.LOOKUP_ORDER, heldOrderId(i), ResponsePlan.gate("g-" + i)));

            withCreate((gateway, create, lookup) -> {
                List<Future<PaymentObservation>> held = new ArrayList<>();
                IntStream.range(0, 10).forEach(i -> held.add(callers.submit(() -> gateway.findByOrderId(heldOrderId(i)))));
                awaitGatesWaiting(10);

                long startedAt = System.nanoTime();
                PaymentObservation created = gateway.create(request("LP-ORD-0000000730"));
                Duration createElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
                PaymentObservation lookedUp = gateway.findByOrderId("LP-ORD-0000000731");
                FixtureState whileFull = pg.state();
                IntStream.range(0, 10).forEach(i -> pg.releaseGate("g-" + i));
                for (Future<PaymentObservation> future : held) {
                    future.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS);
                }

                System.out.println("[PG CREATE POOL] elapsed=" + createElapsed.toMillis() + "ms");
                assertAll(
                    () -> assertThat(created).isEqualTo(
                        new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT)),
                    () -> assertThat(createElapsed).as("읽기 timeout(1초) 전에 lease timeout")
                        .isLessThan(Duration.ofMillis(900)),
                    () -> assertThat(lookedUp).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                    () -> assertThat(whileFull.counts().createReceived()).isZero(),
                    () -> assertThat(whileFull.counts().lookupByOrderReceived()).isEqualTo(10),
                    () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero()
                );
            });
        }
    }

    @DisplayName("미발송으로 판정하지 않을 때,")
    @Nested
    class NotNotDispatched {

        @DisplayName("본문 진입 뒤 pgCreate 이름의 거절 예외는 프로그램 오류로 전파되고 표본·POST 가 없다.")
        @Test
        void callNotPermittedInsideBody() {
            withHookedCreate(new PgHttpGateway.CreateCallHook() {
                @Override
                public void afterEntered() {
                    throw CallNotPermittedException.createCallNotPermittedException(createCircuit);
                }
            }, (gateway, create) -> assertAll(
                () -> assertThatThrownBy(() -> gateway.create(request("LP-ORD-0000000740")))
                    .isInstanceOf(PgAdapterProgrammingException.class)
                    .hasCauseInstanceOf(CallNotPermittedException.class),
                () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero(),
                () -> assertThat(pg.state().counts().createReceived()).isZero()
            ));
        }

        @DisplayName("decorator 호출 전의 pgLookup 거절·일반 예외는 미진입이어도 미발송이 아니며 표본 없이 전파된다.")
        @Test
        void exceptionsBeforeDecorator() {
            withHookedCreate(new PgHttpGateway.CreateCallHook() {
                @Override
                public void beforeDecorator() {
                    throw CallNotPermittedException.createCallNotPermittedException(lookupCircuit);
                }
            }, (gateway, create) -> assertAll(
                () -> assertThatThrownBy(() -> gateway.create(request("LP-ORD-0000000741")))
                    .isInstanceOf(CallNotPermittedException.class),
                () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero(),
                () -> assertThat(create.getMetrics().getNumberOfNotPermittedCalls()).isZero()
            ));
            withHookedCreate(new PgHttpGateway.CreateCallHook() {
                @Override
                public void beforeDecorator() {
                    throw new IllegalStateException("요청 준비 오류 주입");
                }
            }, (gateway, create) -> assertAll(
                () -> assertThatThrownBy(() -> gateway.create(request("LP-ORD-0000000742")))
                    .isInstanceOf(IllegalStateException.class),
                () -> assertThat(create.getMetrics().getNumberOfBufferedCalls()).isZero()
            ));
            assertThat(pg.state().counts().createReceived()).isZero();
        }
    }

    @DisplayName("생성만 OPEN 이면 조회가 되고, 조회만 OPEN 이면 생성이 된다.")
    @Test
    void circuitsAreIndependent() {
        withCreate((gateway, create, lookup) -> {
            create.transitionToOpenState();
            PaymentObservation lookedUp = gateway.findByOrderId("LP-ORD-0000000750");
            create.transitionToClosedState();
            lookup.transitionToOpenState();
            PaymentObservation created = gateway.create(request("LP-ORD-0000000751"));

            assertAll(
                () -> assertThat(lookedUp).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.QUERY)),
                () -> assertThat(pg.state().counts().lookupByOrderReceived()).as("생성 OPEN 중 조회 GET").isEqualTo(1),
                () -> assertThat(created).isInstanceOf(PaymentObservation.TransactionObserved.class),
                () -> assertThat(pg.state().counts().createReceived()).as("조회 OPEN 중 생성 POST").isEqualTo(1)
            );
        });
    }

    // 테스트 seam 에서 쓰는 회로. withHookedCreate 가 매번 새 컨텍스트의 인스턴스로 채운다.
    private CircuitBreaker createCircuit;
    private CircuitBreaker lookupCircuit;

    private void withCreate(CreateTest test) {
        withCreate(pg.baseUrl(), test);
    }

    private void withCreate(String baseUrl, CreateTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class, PgHttpGateway.class)
            .withPropertyValues("commerce.pg.base-url=" + baseUrl)
            .run(context -> {
                assertThat(context).hasNotFailed();
                CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
                test.run(context.getBean(PgHttpGateway.class), registry.circuitBreaker("pgCreate"),
                    registry.circuitBreaker("pgLookup"));
            });
    }

    private void withHookedCreate(PgHttpGateway.CreateCallHook hook, HookedTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class)
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> {
                CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
                createCircuit = registry.circuitBreaker("pgCreate");
                lookupCircuit = registry.circuitBreaker("pgLookup");
                PgHttpGateway gateway = new PgHttpGateway(context.getBean("pgRestClient", RestClient.class),
                    context.getBean(PgResponseDecoder.class), context.getBean(PgHttpProperties.class), registry, hook);
                test.run(gateway, createCircuit);
            });
    }

    private static void assertSamples(CircuitBreaker circuit, int successful, int failed) {
        CircuitBreaker.Metrics metrics = circuit.getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).as("성공 표본").isEqualTo(successful);
        assertThat(metrics.getNumberOfFailedCalls()).as("실패 표본").isEqualTo(failed);
    }

    private void awaitGatesWaiting(int count) {
        await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() ->
            pg.state().gates().stream().filter(gate -> gate.status() == GateStatus.WAITING).count() == count);
    }

    private static PaymentRequest request(String providerOrderId) {
        return new PaymentRequest(providerOrderId, 7000, "TEST", "0000-0000-0000-0000");
    }

    private static String heldOrderId(int index) {
        return "LP-ORD-00000007" + (60 + index);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface CreateTest {
        void run(PgHttpGateway gateway, CircuitBreaker create, CircuitBreaker lookup) throws Exception;
    }

    @FunctionalInterface
    private interface HookedTest {
        void run(PgHttpGateway gateway, CircuitBreaker create) throws Exception;
    }
}
