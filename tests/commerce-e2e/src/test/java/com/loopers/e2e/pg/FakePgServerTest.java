package com.loopers.e2e.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.e2e.pg.FakePgServer.CreateResult;
import com.loopers.e2e.pg.FakePgServer.Status;
import com.loopers.e2e.pg.FakePgServer.Transaction;
import com.loopers.infrastructure.order.PgClient;
import com.loopers.infrastructure.order.PgProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 PG 를 실제 PgClient 로 부른다 (Spring 없음). 정상 PENDING → settle → callback·조회 SUCCESS, 응답 유실 → PgClient UNKNOWN 이지만 PG 는 SUCCESS 거래,
 * 중복 create 기록, 다른 사용자 조회 거절, 유실 해제 뒤 기록 보존을 본다. callback 은 테스트가 띄운 수신 서버로 받는다.
 */
class FakePgServerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long AMOUNT = 3_000L;

    private FakePgServer pg;
    private HttpServer callbackReceiver;
    private final BlockingQueue<JsonNode> receivedCallbacks = new LinkedBlockingQueue<>();
    private PgClient client;

    @BeforeEach
    void setUp() throws IOException {
        callbackReceiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        callbackReceiver.createContext("/api/v1/payments/callback", exchange -> {
            try (exchange) {
                receivedCallbacks.add(OBJECT_MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(200, -1);
            }
        });
        callbackReceiver.start();
        String callbackUrl = "http://localhost:" + callbackReceiver.getAddress().getPort() + "/api/v1/payments/callback";

        pg = FakePgServer.start();
        client = new PgClient(RestClient.builder(),
            new PgProperties(pg.baseUrl(), callbackUrl, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }

    @AfterEach
    void tearDown() {
        pg.close();
        callbackReceiver.stop(0);
    }

    private PaymentGatewayResult request(long orderId) {
        return client.request(USER_ID, orderId, "SAMSUNG", "1234-5678-9814-1451", AMOUNT);
    }

    @DisplayName("정상 create 는 PENDING 거래를 저장하고 PENDING 으로 응답한다. 최종 결과 전에는 callback 을 보내지 않는다.")
    @Test
    void acceptsAsPending() {
        PaymentGatewayResult result = request(7L);

        Transaction transaction = pg.transaction(7L).orElseThrow();
        assertThat(result).isEqualTo(new PaymentGatewayResult(Outcome.PENDING, transaction.transactionKey(), AMOUNT));
        assertThat(transaction.status()).isEqualTo(Status.PENDING);
        assertThat(transaction.userId()).isEqualTo(String.valueOf(USER_ID));
        assertThat(transaction.pgOrderId()).isEqualTo("ORDER-7");
        assertThat(transaction.amount()).isEqualTo(AMOUNT);
        assertThat(pg.createAttempts()).extracting(FakePgServer.CreateAttempt::result).containsExactly(CreateResult.ACCEPTED_PENDING);
        assertThat(pg.callbacks()).isEmpty();
        assertThat(receivedCallbacks).isEmpty();
    }

    @DisplayName("settle 은 거래를 SUCCESS 로 바꾸고 요청의 callbackUrl 로 같은 거래 키의 callback 을 보낸다. 이후 조회도 SUCCESS.")
    @Test
    void settlesAndCallsBack() throws InterruptedException {
        String transactionKey = request(7L).transactionKey();

        int callbackResponse = pg.settle(7L, Status.SUCCESS);

        JsonNode callback = receivedCallbacks.poll(5, TimeUnit.SECONDS);
        assertThat(callbackResponse).isEqualTo(200);
        assertThat(callback).isNotNull();
        assertThat(callback.path("orderId").asText()).isEqualTo("ORDER-7");
        assertThat(callback.path("transactionKey").asText()).isEqualTo(transactionKey);
        assertThat(callback.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(callback.path("amount").asLong()).isEqualTo(AMOUNT);
        assertThat(client.find(USER_ID, 7L)).isEqualTo(new PaymentGatewayResult(Outcome.SUCCESS, transactionKey, AMOUNT));
        assertThat(pg.callbacks()).singleElement().satisfies(sent -> {
            assertThat(sent.status()).isEqualTo(Status.SUCCESS);
            assertThat(sent.responseStatus()).isEqualTo(200);
        });
    }

    @DisplayName("응답 유실 대상은 SUCCESS 거래를 저장한 뒤 빈 본문으로 응답한다: PgClient 는 UNKNOWN, PG 조회는 같은 거래 SUCCESS, callback 은 없다.")
    @Test
    void dropsSuccessResponse() {
        pg.dropResponsesFor(List.of(7L));

        PaymentGatewayResult result = request(7L);

        Transaction transaction = pg.transaction(7L).orElseThrow();
        assertThat(pg.isDropping()).isTrue();
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(transaction.status()).isEqualTo(Status.SUCCESS);
        assertThat(pg.createAttempts()).extracting(FakePgServer.CreateAttempt::result).containsExactly(CreateResult.SUCCEEDED_RESPONSE_DROPPED);
        assertThat(client.find(USER_ID, 7L)).isEqualTo(new PaymentGatewayResult(Outcome.SUCCESS, transaction.transactionKey(), AMOUNT));
        assertThat(pg.callbacks()).isEmpty();
    }

    @DisplayName("응답 유실은 지정한 주문만 대상이다. 같은 시점의 다른 주문은 정상 PENDING.")
    @Test
    void dropsOnlyTargets() {
        pg.dropResponsesFor(List.of(7L));

        PaymentGatewayResult other = request(8L);

        assertThat(other.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(pg.transaction(8L).orElseThrow().status()).isEqualTo(Status.PENDING);
    }

    @DisplayName("이미 거래가 있는 주문의 create 는 중복 시도로 기록하고 409 로 거절한다. 거래는 그대로이고 create 수는 2.")
    @Test
    void recordsDuplicateCreate() {
        request(7L);
        Transaction first = pg.transaction(7L).orElseThrow();

        PaymentGatewayResult second = request(7L);

        assertThat(second.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(pg.createCount(7L)).isEqualTo(2);
        assertThat(pg.duplicateCreates()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.pgOrderId()).isEqualTo("ORDER-7");
            assertThat(duplicate.transactionKey()).isEqualTo(first.transactionKey());
        });
        assertThat(pg.transactions()).containsExactly(first);
    }

    @DisplayName("다른 사용자의 조회는 404 라 PgClient 는 UNKNOWN. 조회 기록은 남고 거래는 변하지 않는다.")
    @Test
    void rejectsLookupByOtherUser() {
        request(7L);
        Transaction before = pg.transaction(7L).orElseThrow();

        PaymentGatewayResult result = client.find(OTHER_USER_ID, 7L);

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(pg.lookups()).singleElement().satisfies(lookup -> {
            assertThat(lookup.userId()).isEqualTo(String.valueOf(OTHER_USER_ID));
            assertThat(lookup.responseStatus()).isEqualTo(404);
        });
        assertThat(pg.transaction(7L)).contains(before);
    }

    @DisplayName("거래가 없는 주문 조회는 404 라 UNKNOWN.")
    @Test
    void unknownForMissingTransaction() {
        assertThat(client.find(USER_ID, 99L).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @DisplayName("응답 유실을 해제해도 저장한 거래·create 기록은 남고, 다음 create 는 정상 PENDING.")
    @Test
    void keepsEvidenceAfterStopDropping() {
        pg.dropResponsesFor(List.of(7L));
        request(7L);
        List<Transaction> transactionsBefore = pg.transactions();

        pg.stopDropping();
        PaymentGatewayResult next = request(8L);

        assertThat(pg.isDropping()).isFalse();
        assertThat(pg.transaction(7L).orElseThrow()).isEqualTo(transactionsBefore.get(0));
        assertThat(pg.createCount(7L)).isEqualTo(1);
        assertThat(next.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(client.find(USER_ID, 7L).outcome()).isEqualTo(Outcome.SUCCESS);
    }
}
