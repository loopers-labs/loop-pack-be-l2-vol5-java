 package com.loopers.infrastructure.payment.client;

import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.support.pg.TestPgGateways;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgServer.LookupStep;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 조회 경로의 재조회 · 회로 (5주차 설계 5장). 장애는 확률 대신 순서를 정한 응답으로 냄 */
class TestPgLookupResilienceTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(200);
    private static final Duration OPEN_WAIT = Duration.ofMillis(300);

    private TestPgServer pg;

    @BeforeEach
    void setUp() {
        pg = TestPgServer.start();
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    private static LookupStep fail() {
        return LookupStep.respond(500, "{}");
    }

    private static LookupStep ok() {
        return LookupStep.normal();
    }

    private static LookupStep[] steps(int failures, int successes) {
        List<LookupStep> steps = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            steps.add(fail());
        }
        for (int i = 0; i < successes; i++) {
            steps.add(ok());
        }
        return steps.toArray(LookupStep[]::new);
    }

    @DisplayName("재조회는, ")
    @Nested
    class RetryOnLookup {
        /** 재조회 대기만 짧게 하고 회로는 열리지 않게 둠 */
        private final TestPgLookupPolicyProperties policy =
            new TestPgLookupPolicyProperties(3, Duration.ofMillis(10), 2, 100, 100, 100, Duration.ofSeconds(10), 2);

        private TestPgPaymentGateway gateway;

        @BeforeEach
        void setUp() {
            gateway = TestPgGateways.gateway(pg.baseUrl(), READ_TIMEOUT, policy);
        }

        @DisplayName("5xx 두 번 뒤 정상이면, 세 번째 시도의 거래 목록을 돌려준다.")
        @Test
        void returnsResult_whenThirdAttemptSucceeds() {
            // arrange
            pg.lookupWillRespondInOrder(fail(), fail(), ok());

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Found.class),
                () -> assertThat(pg.lookupCount()).isEqualTo(3)
            );
        }

        @DisplayName("세 번 모두 5xx 면, 조회 불가이고 네 번째 시도는 없다.")
        @Test
        void returnsUnavailable_whenAllAttemptsFail() {
            // arrange
            pg.lookupWillRespondInOrder(fail(), fail(), fail(), ok());

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class),
                () -> assertThat(pg.lookupCount()).isEqualTo(3)
            );
        }

        @DisplayName("응답 대기 한도를 넘기면, 다시 조회한다.")
        @Test
        void retries_whenTimedOut() {
            // arrange
            pg.lookupWillRespondInOrder(LookupStep.delay(Duration.ofSeconds(1)), ok());

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Found.class),
                () -> assertThat(pg.lookupCount()).isEqualTo(2)
            );
        }

        @DisplayName("PG 에 연결할 수 없으면, 세 번 시도한 뒤 조회 불가다.")
        @Test
        void retries_whenConnectionFails() {
            // arrange
            TestPgServer stopped = TestPgServer.start();
            String baseUrl = stopped.baseUrl();
            stopped.close();
            CircuitBreaker circuitBreaker = TestPgResilienceConfig.lookupCircuitBreaker(policy);
            TestPgPaymentGateway unreachable = TestPgGateways.gateway(baseUrl, READ_TIMEOUT, circuitBreaker, TestPgResilienceConfig.lookupRetry(policy));

            // act
            PaymentLookupResult result = unreachable.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class),
                () -> assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3)
            );
        }

        @DisplayName("거래가 없으면, 빈 목록으로 한 번에 끝난다.")
        @Test
        void doesNotRetry_whenNoTransaction() {
            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(new PaymentLookupResult.Found(List.of())),
                () -> assertThat(pg.lookupCount()).isEqualTo(1)
            );
        }

        static Stream<Arguments> contractViolations() {
            return Stream.of(
                Arguments.of("200 이지만 배열이 아님", 200, "{}"),
                Arguments.of("거래 형식이 다름", 200, "[{\"orderId\":\"" + ORDER_ID + "\"}]"),
                Arguments.of("목록 조회의 404", 404, "{}")
            );
        }

        @DisplayName("계약과 다른 응답이면, 같은 응답이 반복될 것이라 다시 조회하지 않고 조회 불가다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("contractViolations")
        void doesNotRetry_whenContractIsViolated(String name, int status, String body) {
            // arrange
            pg.lookupWillRespond(status, body);

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class),
                () -> assertThat(pg.lookupCount()).isEqualTo(1)
            );
        }

        @DisplayName("기본 설정이면, 100ms → 200ms 를 기다리며 세 번 시도한다.")
        @Test
        void waitsBetweenAttempts_withDefaultPolicy() {
            // arrange
            TestPgPaymentGateway defaultGateway = TestPgGateways.gateway(pg.baseUrl(), READ_TIMEOUT, TestPgGateways.defaultPolicy());
            pg.lookupWillRespond(500, "{}");
            long startedAt = System.nanoTime();

            // act
            defaultGateway.findByProviderOrderId(ORDER_ID);

            // assert
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertAll(
                () -> assertThat(pg.lookupCount()).isEqualTo(3),
                () -> assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300))
            );
        }

        @DisplayName("생성 요청은, 5xx 를 받아도 다시 보내지 않는다.")
        @Test
        void neverRetriesCreate() {
            // arrange
            pg.willRespondToCreate(500, "{}");

            // act
            gateway.create(new PaymentGatewayRequest(ORDER_ID, "TEST", "0000-0000-0000-0000", 7_000L));

            // assert
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("회로는, (표본 창 10 · 최소 표본 5 · 실패율 50%)")
    @Nested
    class CircuitBreaking {
        private CircuitBreaker circuitBreaker;
        private TestPgPaymentGateway gateway;

        /** 재조회 없이 조회 1 회를 표본 1 건으로 셈. OPEN 대기만 짧게 둠 */
        @BeforeEach
        void setUp() {
            TestPgLookupPolicyProperties policy = new TestPgLookupPolicyProperties(1, Duration.ofMillis(10), 2, 10, 5, 50, OPEN_WAIT, 2);
            circuitBreaker = TestPgResilienceConfig.lookupCircuitBreaker(policy);
            gateway = TestPgGateways.gateway(pg.baseUrl(), READ_TIMEOUT, circuitBreaker, TestPgResilienceConfig.lookupRetry(policy));
        }

        private void lookupTimes(int times) {
            for (int i = 0; i < times; i++) {
                gateway.findByProviderOrderId(ORDER_ID);
            }
        }

        private void open() {
            pg.lookupWillRespondInOrder(steps(5, 0));
            lookupTimes(5);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("실패율이 기준 미만(5 건 중 2 건, 40%)이면, CLOSED 를 유지한다.")
        @Test
        void staysClosed_belowThreshold() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            pg.lookupWillRespondInOrder(steps(2, 3));
            lookupTimes(5);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("실패율이 기준 이상(5 건 중 3 건, 60%)이면, OPEN 이 된다.")
        @Test
        void opens_atThreshold() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            pg.lookupWillRespondInOrder(steps(3, 2));
            lookupTimes(5);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("최소 표본(5 건)에 못 미치면, 모두 실패해도 CLOSED 를 유지한다.")
        @Test
        void staysClosed_beforeMinimumCalls() {
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            pg.lookupWillRespondInOrder(steps(4, 0));
            lookupTimes(4);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("계약과 다른 응답도, 회로 실패로 센다.")
        @Test
        void countsContractViolationAsFailure() {
            pg.lookupWillRespond(200, "{}");

            lookupTimes(5);

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("빈 목록은 정상 결과라, 회로 실패로 세지 않는다.")
        @Test
        void doesNotCountEmptyListAsFailure() {
            lookupTimes(5);

            assertAll(
                () -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED),
                () -> assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero()
            );
        }

        @DisplayName("OPEN 동안은, PG 에 조회를 보내지 않고 조회 불가를 돌려준다.")
        @Test
        void sendsNothing_whileOpen() {
            // arrange
            open();
            int lookupsWhenOpened = pg.lookupCount();

            // act
            PaymentLookupResult result = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(result).isInstanceOf(PaymentLookupResult.Unavailable.class),
                () -> assertThat(pg.lookupCount()).isEqualTo(lookupsWhenOpened)
            );
        }

        @DisplayName("대기 뒤 탐색 2 건이 성공하면, HALF_OPEN 을 거쳐 CLOSED 로 돌아가 조회를 재개한다.")
        @Test
        void closes_whenProbesSucceedAfterWait() throws InterruptedException {
            // arrange
            open();
            Thread.sleep(OPEN_WAIT.plusMillis(100).toMillis());

            // act
            PaymentLookupResult first = gateway.findByProviderOrderId(ORDER_ID);
            CircuitBreaker.State afterFirstProbe = circuitBreaker.getState();
            PaymentLookupResult second = gateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertAll(
                () -> assertThat(first).isInstanceOf(PaymentLookupResult.Found.class),
                () -> assertThat(afterFirstProbe).isEqualTo(CircuitBreaker.State.HALF_OPEN),
                () -> assertThat(second).isInstanceOf(PaymentLookupResult.Found.class),
                () -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
            );
        }

        @DisplayName("대기 뒤 탐색이 실패하면, 다시 OPEN 이 된다.")
        @Test
        void reopens_whenProbeFailsAfterWait() throws InterruptedException {
            // arrange
            open();
            Thread.sleep(OPEN_WAIT.plusMillis(100).toMillis());
            pg.lookupWillRespondInOrder(fail(), fail());

            // act
            lookupTimes(2);

            // assert
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @DisplayName("재조회를 켜면, 재조회의 매 시도가 회로 표본 하나다.")
        @Test
        void countsEachRetryAttemptAsSample() {
            // arrange
            TestPgLookupPolicyProperties policy = new TestPgLookupPolicyProperties(3, Duration.ofMillis(10), 2, 10, 5, 50, OPEN_WAIT, 2);
            CircuitBreaker retried = TestPgResilienceConfig.lookupCircuitBreaker(policy);
            TestPgPaymentGateway retryingGateway = TestPgGateways.gateway(pg.baseUrl(), READ_TIMEOUT, retried, TestPgResilienceConfig.lookupRetry(policy));
            pg.lookupWillRespond(500, "{}");

            // act
            retryingGateway.findByProviderOrderId(ORDER_ID);

            // assert
            assertThat(retried.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        }
    }
}
