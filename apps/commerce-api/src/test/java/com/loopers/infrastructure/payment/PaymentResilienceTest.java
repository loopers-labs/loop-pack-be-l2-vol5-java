package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusCheckResult;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.support.pg.FakePgServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회의 재시도·회로 차단이 실제 프록시를 거쳐 적용되는지, 그리고 결제 생성에는 적용되지 않는지 확인한다. 기준은 상태가
 * 아니라 fixture 가 받은 <b>실제 호출 수</b>와 회로가 센 표본이다.
 *
 * <p>회로가 OPEN 에서 HALF_OPEN 으로 넘어가는 대기 시간만 짧게 덮어쓴다. 나머지 수치는 운영 설정 그대로다
 * (window 10, 최소 5건, 실패율 50%, HALF_OPEN 탐색 2건, 조회 재시도 최대 3회).
 */
@SpringBootTest
@TestPropertySource(properties = "resilience4j.circuitbreaker.instances.pgLookup.wait-duration-in-open-state=400ms")
class PaymentResilienceTest {

    private static final String KEY = "TEST-TX-0001";
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final Duration OPEN_WAIT = Duration.ofMillis(400);

    private static FakePgServer pg;

    @BeforeAll
    static void startPg() throws IOException {
        pg = FakePgServer.start();
    }

    @AfterAll
    static void stopPg() {
        pg.close();
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.base-url", () -> pg.baseUrl());
    }

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private PgHttpClient client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        pg.reset();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker(PgHttpClient.LOOKUP_RESILIENCE);
        circuitBreaker.reset();
    }

    // 항상 500 인 조회를 두 번 부르면 실제 호출 5건에서 실패율 100% 로 OPEN 된다 (3 + 2, 여섯 번째는 회로가 막는다).
    private void openCircuit() {
        pg.failLookups(500);
        gateway.checkPaymentStatusByTransactionKey(KEY);
        gateway.checkPaymentStatusByTransactionKey(KEY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void waitForHalfOpen() throws InterruptedException {
        Thread.sleep(OPEN_WAIT.toMillis() + 150);
    }

    @DisplayName("프록시 적용을 확인할 때,")
    @Nested
    class Proxy {
        @DisplayName("조회를 하는 client 는 AOP 프록시다. annotation 이 실제로 적용되는 빈이다.")
        @Test
        void clientIsAopProxy() {
            assertThat(AopUtils.isAopProxy(client)).isTrue();
        }

        @DisplayName("조회가 프록시를 거치면 회로가 호출을 센다. 거치지 않으면 0건이라 이 검사가 깨진다.")
        @Test
        void circuitCountsLookupCalls() {
            // arrange
            pg.addTransaction(ORDER_ID, 7_000, "PENDING");

            // act
            gateway.checkPaymentStatusByTransactionKey(KEY);
            gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
        }
    }

    @DisplayName("조회가 계속 실패할 때,")
    @Nested
    class Retry {
        @DisplayName("한 번 조회하면 실제 호출은 3회, 회로가 센 실패도 3건이다 (Retry 가 CircuitBreaker 바깥).")
        @Test
        void callsThreeTimes_andCircuitCountsEachRealCall() {
            // arrange
            pg.failLookups(500);

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(3);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("주문 식별자 조회도 같은 규칙이다. 실제 호출 3회, 실패 3건.")
        @Test
        void callsThreeTimes_forOrderLookupToo() {
            // arrange
            pg.failLookups(503);

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
            assertThat(pg.lookupByOrderCalls()).isEqualTo(3);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        }

        @DisplayName("두 번째 조회의 두 번째 호출에서 표본 5건이 차 회로가 OPEN 되고, 거기서 재시도를 멈춘다 (실제 호출 5회).")
        @Test
        void stopsRetrying_whenCircuitOpens() {
            // arrange
            pg.failLookups(500);

            // act
            gateway.checkPaymentStatusByTransactionKey(KEY);
            gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(5);
        }

        @DisplayName("재시도 중 성공하면 거기서 멈추고 결과를 돌려준다. 실제 호출 2회, 실패 1건, 성공 1건.")
        @Test
        void returnsResult_whenRetrySucceeds() {
            // arrange: 처음 한 번만 실패하고 그다음은 정상. 이 fixture 가 처음 발급하는 거래 키는 TEST-TX-0001 이다.
            pg.addTransaction(ORDER_ID, 7_000, "SUCCESS");
            pg.failNextLookups(1, 500);

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(2);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        }
    }

    @DisplayName("회로가 OPEN 일 때,")
    @Nested
    class Open {
        @DisplayName("조회는 실제 호출 없이 조회 불가를 돌려준다. fixture 가 받은 호출이 늘지 않는다.")
        @Test
        void doesNotCallPg_andReturnsUnavailable() {
            // arrange
            openCircuit();
            int callsBefore = pg.lookupByKeyCalls();

            // act
            PgPaymentStatusCheckResult byKey = gateway.checkPaymentStatusByTransactionKey(KEY);
            PgPaymentStatusCheckResult byOrder = gateway.checkPaymentStatusByOrder(ORDER_ID);

            // assert
            assertThat(byKey.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
            assertThat(byOrder.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(callsBefore);
            assertThat(pg.lookupByOrderCalls()).isZero();
        }

        @DisplayName("막힌 호출은 재시도하지 않는다. 한 번 시도하고 바로 조회 불가가 된다.")
        @Test
        void doesNotRetryBlockedCalls() {
            // arrange
            openCircuit();
            long notPermittedBefore = circuitBreaker.getMetrics().getNumberOfNotPermittedCalls();

            // act
            gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls() - notPermittedBefore).isEqualTo(1);
        }

        @DisplayName("대기 뒤 PG 가 회복했으면 탐색 호출 2건이 성공해 CLOSED 로 돌아오고 조회가 다시 나간다.")
        @Test
        void recoversToClosed_afterWait() throws InterruptedException {
            // arrange
            openCircuit();
            int callsBefore = pg.lookupByKeyCalls();
            pg.resetLookupFaults();
            pg.addTransaction(ORDER_ID, 7_000, "PENDING");
            waitForHalfOpen();

            // act & assert
            assertThat(gateway.checkPaymentStatusByTransactionKey(KEY).outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            assertThat(gateway.checkPaymentStatusByTransactionKey(KEY).outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(pg.lookupByKeyCalls() - callsBefore).isEqualTo(2);
        }

        @DisplayName("대기 뒤에도 PG 가 실패하면 탐색 호출 2건만 나가고 다시 OPEN 된다 (재시도가 탐색을 늘리지 못한다).")
        @Test
        void opensAgain_whenProbesFail() throws InterruptedException {
            // arrange
            openCircuit();
            int callsBefore = pg.lookupByKeyCalls();
            waitForHalfOpen();

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(pg.lookupByKeyCalls() - callsBefore).isEqualTo(2);
        }

        @DisplayName("OPEN 이어도 결제 결과를 정하지 않는다. 조회 불가일 뿐 거래 없음이나 실패가 아니다.")
        @Test
        void doesNotDecideResult() {
            // arrange
            pg.addTransaction(ORDER_ID, 7_000, "SUCCESS");
            openCircuit();

            // act
            PgPaymentStatusCheckResult lookup = gateway.checkPaymentStatusByTransactionKey(KEY);

            // assert
            assertThat(lookup.outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.COULD_NOT_CHECK);
        }
    }

    @DisplayName("정상 응답일 때,")
    @Nested
    class NormalResponses {
        @DisplayName("거래 없음(404)과 빈 목록은 회로의 실패로 세지 않는다. 몇 번을 받아도 CLOSED 다.")
        @Test
        void doesNotCountNotFoundAndEmptyAsFailure() {
            // act
            for (int i = 0; i < 12; i++) {
                assertThat(gateway.checkPaymentStatusByTransactionKey("TEST-TX-9999").outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.KEY_NOT_FOUND);
                assertThat(gateway.checkPaymentStatusByOrder(ORDER_ID).outcome()).isEqualTo(PgPaymentStatusCheckResult.Outcome.ANSWERED);
            }

            // assert
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(pg.lookupByKeyCalls()).isEqualTo(12);
        }
    }

    @DisplayName("결제를 생성할 때,")
    @Nested
    class Create {
        @DisplayName("실패해도 재시도하지 않는다. 요청 수만큼만 PG 에 나가고, 조회용 회로는 표본을 세지 않는다.")
        @Test
        void neverRetries_andIsNotCountedByLookupCircuit() {
            // arrange
            pg.onCreate(FakePgServer.respond(500, "{\"code\":\"ERROR\"}"));

            // act
            for (int i = 0; i < 6; i++) {
                assertThat(gateway.requestPayment(request()).outcome()).isEqualTo(PgPaymentRequestResult.Outcome.UNKNOWN);
            }

            // assert
            assertThat(pg.createCalls()).isEqualTo(6);
            assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @DisplayName("조회 회로가 OPEN 이어도 막히지 않는다. 회로는 결제 생성의 가부를 정하지 않는다.")
        @Test
        void isNotBlockedByOpenLookupCircuit() {
            // arrange
            openCircuit();

            // act
            PgPaymentRequestResult result = gateway.requestPayment(request());

            // assert
            assertThat(result.outcome()).isEqualTo(PgPaymentRequestResult.Outcome.ACCEPTED);
            assertThat(pg.createCalls()).isEqualTo(1);
        }

        private PgPaymentRequestCommand request() {
            return new PgPaymentRequestCommand(ORDER_ID, "TEST", "0000-0000-0000-0000", 7_000);
        }
    }
}
