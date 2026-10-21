package com.loopers.payment.adapter.out.pg;

import com.loopers.config.PgConfig;
import com.loopers.payment.application.port.out.PgLookupUnavailableException;
import com.loopers.payment.application.port.out.PgNotSentException;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgRejectedException;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.application.port.out.PgUnknownResultException;
import com.loopers.payment.domain.ExternalPaymentStatus;
import com.loopers.support.pg.LocalTestPgServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 로컬 테스트 PG 계약과 회복 전략 (plan.md 6·7장, G-1~G-11).
 * DB 없이 어댑터·설정·Resilience4j 자동 설정만 띄운다. 회로·재시도 수치는 application.yml 값을 그대로 쓰고,
 * 읽기 timeout만 테스트가 빨리 끝나도록 줄인다 (생성 500ms, 조회 300ms).
 */
@SpringBootTest(
    classes = LocalPgPaymentGatewayAdapterTest.PgAdapterContext.class,
    properties = {
        "pg.local.create-read-timeout=500ms",
        "pg.local.lookup-read-timeout=300ms",
    })
class LocalPgPaymentGatewayAdapterTest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final String ORDER_ID = "LP-ORD-0000000042";

    @Configuration
    @ImportAutoConfiguration({CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class})
    @Import({PgConfig.class, LocalPgPaymentGatewayAdapter.class})
    static class PgAdapterContext {}

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
        registry.add("pg.local.callback-url", () -> CALLBACK_URL);
    }

    @Autowired
    private LocalPgPaymentGatewayAdapter adapter;

    @Autowired
    private PgProperties properties;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() {
        PG.reset();
        createCircuit().reset();
        lookupCircuit().reset();
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    private CircuitBreaker createCircuit() {
        return circuitBreakerRegistry.circuitBreaker("pgCreate");
    }

    private CircuitBreaker lookupCircuit() {
        return circuitBreakerRegistry.circuitBreaker("pgLookup");
    }

    private static PgPaymentRequest paymentRequest() {
        return new PgPaymentRequest(ORDER_ID, "SAMSUNG", "1234-5678-9814-1451", 5000L);
    }

    @DisplayName("거래 생성 (POST, 재시도 없음)")
    @Nested
    class Create {

        @DisplayName("G-1 수락되면 PENDING 거래와 transactionKey를 돌려주고, PG는 X-USER-ID 135135와 설정의 callbackUrl을 받는다")
        @Test
        void returnsPendingTransaction() {
            // act
            PgTransaction transaction = adapter.request(paymentRequest());

            // assert
            assertThat(transaction.transactionKey()).isNotBlank();
            assertThat(transaction.providerOrderId()).isEqualTo(ORDER_ID);
            assertThat(transaction.amount()).isEqualTo(5000L);
            assertThat(transaction.status()).isEqualTo(ExternalPaymentStatus.PENDING);
            assertThat(PG.transactionsCreated()).isEqualTo(1);
            LocalTestPgServer.CreateRecord received = PG.createRecords().get(0);
            assertThat(received.userId()).isEqualTo("135135");
            assertThat(received.callbackUrl()).isEqualTo(CALLBACK_URL);
            assertThat(received.cardType()).isEqualTo("SAMSUNG");
            assertThat(received.cardNoPresent()).isTrue();
            assertThat(received.amount()).isEqualTo(5000L);
        }

        @DisplayName("G-2 PG가 400으로 거절하면 PgRejectedException이고, 거래는 0건이다")
        @Test
        void rejectedWhenPgReturns400() {
            // arrange
            PG.rejectNextCreate();

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgRejectedException.class);
            assertThat(PG.createCalls()).isEqualTo(1);
            assertThat(PG.transactionsCreated()).isZero();
        }

        @DisplayName("G-3 거래는 생겼지만 응답이 읽기 timeout보다 늦으면 PgUnknownResultException이고, 숨은 재시도 없이 생성 호출·거래는 각 1이며, 주문 단위 조회로 그 거래를 찾는다")
        @Test
        void unknownWhenResponseIsLost() {
            // arrange
            PG.delayNextCreateResponse(Duration.ofMillis(1500));

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgUnknownResultException.class);
            await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))
                .until(() -> PG.createCalls() == 1 && PG.transactionsCreated() == 1);
            List<PgTransaction> found = adapter.findByProviderOrderId(ORDER_ID);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).status()).isEqualTo(ExternalPaymentStatus.PENDING);
            assertThat(found.get(0).amount()).isEqualTo(5000L);
        }

        @DisplayName("G-3 거래는 생겼지만 응답 없이 연결이 끊기면 PgUnknownResultException이고, HTTP 클라이언트가 POST를 다시 보내지 않아 생성 호출·거래는 각 1이다")
        @Test
        void unknownWithoutHiddenRetryWhenConnectionDropped() {
            // arrange
            PG.dropNextCreateConnection();

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgUnknownResultException.class);
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> PG.createCalls() == 1 && PG.transactionsCreated() == 1);
        }

        @DisplayName("G-4 PG에 연결하지 못하면(connection refused) PgNotSentException이다")
        @Test
        void notSentWhenConnectionRefused() throws IOException {
            // arrange
            PgProperties downPg = new PgProperties(
                "http://127.0.0.1:" + unusedPort(), properties.userId(), properties.callbackUrl(),
                properties.connectTimeout(), properties.createReadTimeout(), properties.lookupReadTimeout());
            LocalPgPaymentGatewayAdapter downAdapter = new LocalPgPaymentGatewayAdapter(downPg, circuitBreakerRegistry, retryRegistry);

            // act & assert
            assertThatThrownBy(() -> downAdapter.request(paymentRequest())).isInstanceOf(PgNotSentException.class);
        }

        @DisplayName("G-5 거래를 만든 뒤 500이 오면 거절이 아니라 PgUnknownResultException이다")
        @Test
        void unknownWhenServerErrorAfterCreating() {
            // arrange
            PG.failNextCreateAfterCreating();

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgUnknownResultException.class);
            assertThat(PG.transactionsCreated()).isEqualTo(1);
        }

        @DisplayName("G-5 거래를 만든 뒤 응답 본문을 해석할 수 없으면 PgUnknownResultException이다")
        @Test
        void unknownWhenResponseIsUnreadable() {
            // arrange
            PG.garbleNextCreateResponse();

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgUnknownResultException.class);
            assertThat(PG.transactionsCreated()).isEqualTo(1);
        }

        @DisplayName("G-6 생성 실패가 쌓여 회로가 열리면 다음 요청은 보내지 않고 PgNotSentException이며, PG 생성 호출 수는 늘지 않는다")
        @Test
        void notSentWhenCreateCircuitOpens() {
            // arrange — 최소 표본 5회가 모두 500
            for (int i = 0; i < 5; i++) {
                PG.failNextCreateWithoutCreating();
                assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgUnknownResultException.class);
            }
            assertThat(createCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // act & assert
            assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgNotSentException.class);
            assertThat(PG.createCalls()).isEqualTo(5);
        }

        @DisplayName("G-7 400 거절은 회로 실패로 세지 않는다 — 10번 거절돼도 회로는 닫혀 있고 다음 생성은 PG에 도착한다")
        @Test
        void rejectionsDoNotOpenCreateCircuit() {
            // arrange
            for (int i = 0; i < 10; i++) {
                PG.rejectNextCreate();
                assertThatThrownBy(() -> adapter.request(paymentRequest())).isInstanceOf(PgRejectedException.class);
            }

            // act
            PgTransaction transaction = adapter.request(paymentRequest());

            // assert
            assertThat(createCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(transaction.status()).isEqualTo(ExternalPaymentStatus.PENDING);
            assertThat(PG.createCalls()).isEqualTo(11);
        }
    }

    @DisplayName("거래 조회 (GET, 재시도 있음)")
    @Nested
    class Lookup {

        @DisplayName("G-8 조회가 500을 두 번 받은 뒤 성공하면 결과를 돌려주고, PG 조회 호출은 3회다")
        @Test
        void retriesServerErrorsThenSucceeds() {
            // arrange
            PgTransaction created = adapter.request(paymentRequest());
            PG.complete(created.transactionKey(), "SUCCESS", null);
            PG.failNextLookups(2);

            // act
            Optional<PgTransaction> found = adapter.findByTransactionKey(created.transactionKey());

            // assert
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo(ExternalPaymentStatus.SUCCESS);
            assertThat(found.get().amount()).isEqualTo(5000L);
            assertThat(PG.lookupCalls()).isEqualTo(3);
        }

        @DisplayName("G-8 조회가 읽기 timeout을 한 번 넘긴 뒤 성공하면 결과를 돌려주고, PG 조회 호출은 2회다")
        @Test
        void retriesTimeoutThenSucceeds() {
            // arrange
            PgTransaction created = adapter.request(paymentRequest());
            PG.complete(created.transactionKey(), "FAILED", "한도 초과");
            PG.delayNextLookups(1, Duration.ofMillis(800));

            // act
            Optional<PgTransaction> found = adapter.findByTransactionKey(created.transactionKey());

            // assert
            assertThat(found).isPresent();
            assertThat(found.get().status()).isEqualTo(ExternalPaymentStatus.FAILED);
            assertThat(found.get().reason()).isEqualTo("한도 초과");
            assertThat(PG.lookupCalls()).isEqualTo(2);
        }

        @DisplayName("G-9 조회가 계속 500이면 3회 시도 뒤 PgLookupUnavailableException이다")
        @Test
        void unavailableAfterThreeAttempts() {
            // arrange
            PG.failNextLookups(10);

            // act & assert
            assertThatThrownBy(() -> adapter.findByTransactionKey("20261021:TR:none"))
                .isInstanceOf(PgLookupUnavailableException.class);
            assertThat(PG.lookupCalls()).isEqualTo(3);
        }

        @DisplayName("G-9 없는 거래(404)는 재시도 없이 빈 값이고, PG 조회 호출은 1회다")
        @Test
        void emptyWhenNotFound() {
            // act
            Optional<PgTransaction> found = adapter.findByTransactionKey("20261021:TR:none");

            // assert
            assertThat(found).isEmpty();
            assertThat(PG.lookupCalls()).isEqualTo(1);
        }

        @DisplayName("G-10 조회 회로가 열려 있으면 PG를 부르지 않고 PgLookupUnavailableException이다")
        @Test
        void unavailableWhenLookupCircuitOpen() {
            // arrange
            lookupCircuit().transitionToOpenState();

            // act & assert
            assertThatThrownBy(() -> adapter.findByTransactionKey("20261021:TR:any"))
                .isInstanceOf(PgLookupUnavailableException.class);
            assertThatThrownBy(() -> adapter.findByProviderOrderId(ORDER_ID))
                .isInstanceOf(PgLookupUnavailableException.class);
            assertThat(PG.lookupCalls()).isZero();
        }

        @DisplayName("G-11 주문 단위 조회는 0건이면 빈 목록이다")
        @Test
        void emptyListWhenNoTransaction() {
            // act
            List<PgTransaction> found = adapter.findByProviderOrderId(ORDER_ID);

            // assert
            assertThat(found).isEmpty();
            assertThat(PG.lookupCalls()).isEqualTo(1);
        }

        @DisplayName("G-11 같은 주문에 거래가 2건이면 둘 다 돌려준다 (PG는 같은 orderId의 새 POST를 막지 않는다)")
        @Test
        void returnsAllTransactionsOfOrder() {
            // arrange
            PgTransaction first = adapter.request(paymentRequest());
            PgTransaction second = adapter.request(paymentRequest());
            adapter.request(new PgPaymentRequest("LP-ORD-0000000099", "KB", "1111-2222-3333-4444", 7000L));

            // act
            List<PgTransaction> found = adapter.findByProviderOrderId(ORDER_ID);

            // assert
            assertThat(found).extracting(PgTransaction::transactionKey)
                .containsExactlyInAnyOrder(first.transactionKey(), second.transactionKey());
            assertThat(found).extracting(PgTransaction::providerOrderId).containsOnly(ORDER_ID);
        }
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
