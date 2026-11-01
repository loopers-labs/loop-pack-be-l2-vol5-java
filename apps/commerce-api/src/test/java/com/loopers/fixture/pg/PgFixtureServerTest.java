package com.loopers.fixture.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("테스트 PG fixture 는 실제 HTTP 로 거래를 만들고 조회한다.")
class PgFixtureServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_URL = "http://127.0.0.1:18080/api/v1/payments/callback";

    private PgFixtureServer pg;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        pg = PgFixtureServer.start();
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    /** client 정리가 실패해도 fixture 를 닫고, 두 실패를 모두 남긴다. HttpClient.close() 는 종료까지 사실상 무기한 대기라 쓰지 않는다. */
    @AfterEach
    void tearDown() throws Throwable {
        Throwable failure = null;
        try {
            if (client != null) {
                shutdownClientWithin(CLIENT_CLOSE_TIMEOUT);
            }
        } catch (Throwable t) {
            failure = t;
        }
        try {
            if (pg != null) {
                pg.close();
                assertThat(pg.isTerminated()).as("PG fixture HTTP worker 종료").isTrue();
            }
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else {
                failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void shutdownClientWithin(Duration timeout) throws InterruptedException {
        client.shutdownNow();
        if (!client.awaitTermination(timeout)) {
            throw new IllegalStateException("HttpClient 종료 미확인");
        }
    }

    @DisplayName("거래를 생성할 때,")
    @Nested
    class Create {

        @DisplayName("한 요청의 본문 수신이 멈춰 있어도 다른 생성·조회 요청은 처리된다.")
        @Test
        void servesOtherRequestsWhileBodyReceptionStalls() throws IOException {
            try (Socket stalled = new Socket("127.0.0.1", URI.create(pg.baseUrl()).getPort())) {
                stalled.setSoTimeout((int) REQUEST_TIMEOUT.toMillis());
                OutputStream out = stalled.getOutputStream();
                out.write(("POST /api/v1/payments HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "X-USER-ID: 135135\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 200\r\n\r\n"
                    + "{\"orderId\":").getBytes(StandardCharsets.UTF_8));
                out.flush();
                await().atMost(REQUEST_TIMEOUT).until(() -> pg.state().counts().createReceived() == 1);

                HttpResponse<String> created = post(validCreateBody("LP-ORD-0000000045", 7000));
                HttpResponse<String> lookedUp = get("/api/v1/payments?orderId=LP-ORD-0000000045");

                assertAll(
                    () -> assertThat(created.statusCode()).isEqualTo(202),
                    () -> assertThat(lookedUp.statusCode()).isEqualTo(200),
                    () -> assertThat(readJson(lookedUp)).hasSize(1)
                );
            }
            assertThat(pg.state().transactions())
                .extracting(TransactionSnapshot::orderId)
                .containsExactly("LP-ORD-0000000045");
        }
    }

    @DisplayName("요청을 거절할 때,")
    @Nested
    class Reject {

        @DisplayName("X-USER-ID 가 없거나 다르거나 여러 개면 세 요청 모두 400 이고 거래를 만들거나 노출하지 않는다.")
        @Test
        void rejectsInvalidProviderUserHeader() {
            String key = transactionKeyOf(post(validCreateBody("LP-ORD-0000000060", 7000)));
            List<List<String>> invalidHeaders = List.of(List.of(), List.of("135136"), List.of("135135", "135135"));

            List<Integer> statuses = new ArrayList<>();
            for (List<String> headerValues : invalidHeaders) {
                Consumer<HttpRequest.Builder> header = builder -> headerValues.forEach(v -> builder.header("X-USER-ID", v));
                statuses.add(send(createRequestBuilder(validCreateBody("LP-ORD-0000000061", 7000)), header).statusCode());
                statuses.add(send(getRequestBuilder("/api/v1/payments/" + key), header).statusCode());
                statuses.add(send(getRequestBuilder("/api/v1/payments?orderId=LP-ORD-0000000060"), header).statusCode());
            }

            assertAll(
                () -> assertThat(statuses).hasSize(9).containsOnly(400),
                () -> assertThat(pg.state().transactions())
                    .extracting(TransactionSnapshot::transactionKey)
                    .containsExactly(key),
                () -> assertThat(pg.state().counts()).isEqualTo(new RequestCounts(4, 3, 3, 0))
            );
        }

    }

    @DisplayName("fixture 수명을 관리할 때,")
    @Nested
    class Lifecycle {

        @DisplayName("동시에 띄운 두 fixture 는 포트·거래·수신 횟수를 공유하지 않는다.")
        @Test
        void isolatesStateBetweenFixtures() {
            PgFixtureServer other = PgFixtureServer.start();
            try {
                String key = transactionKeyOf(post(validCreateBody("LP-ORD-0000000070", 7000)));

                HttpResponse<String> otherLookup = send(
                    HttpRequest.newBuilder(URI.create(other.baseUrl() + "/api/v1/payments/" + key)).GET(), withProviderUser());

                assertAll(
                    () -> assertThat(other.baseUrl()).isNotEqualTo(pg.baseUrl()),
                    () -> assertThat(otherLookup.statusCode()).isEqualTo(404),
                    () -> assertThat(other.state().transactions()).isEmpty(),
                    () -> assertThat(other.state().counts()).isEqualTo(new RequestCounts(0, 1, 0, 0)),
                    () -> assertThat(pg.state().counts()).isEqualTo(new RequestCounts(1, 0, 0, 0))
                );
            } finally {
                other.close();
            }
            assertThat(other.isTerminated()).isTrue();
        }

    }

    /** 저장 타입의 필드 검사는 pg-simulator 모듈의 PgSimulatorHttpContractTest 가 맡는다. */
    @DisplayName("관측 타입은 카드 정보·요청 원문·callbackUrl 필드를 갖지 않는다.")
    @Test
    void storesOnlyContractFields() {
        assertThat(recordComponentNames(TransactionSnapshot.class))
            .containsExactly("transactionKey", "orderId", "amount", "status");
    }

    private static Map<String, Object> validCreateFields(String orderId, long amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", orderId);
        fields.put("cardType", "TEST");
        fields.put("cardNo", CARD_NO);
        fields.put("amount", amount);
        fields.put("callbackUrl", CALLBACK_URL);
        return fields;
    }

    private static String validCreateBody(String orderId, long amount) {
        return toJson(validCreateFields(orderId, amount));
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpRequest.Builder createRequestBuilder(String body) {
        return HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder getRequestBuilder(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(pg.baseUrl() + pathAndQuery)).GET();
    }

    private HttpResponse<String> post(String body) {
        return send(createRequestBuilder(body), withProviderUser());
    }

    private HttpResponse<String> get(String pathAndQuery) {
        return send(getRequestBuilder(pathAndQuery), withProviderUser());
    }

    private static Consumer<HttpRequest.Builder> withProviderUser() {
        return builder -> builder.header("X-USER-ID", "135135");
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, Consumer<HttpRequest.Builder> headers) {
        headers.accept(builder);
        try {
            return client.send(builder.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readJson(HttpResponse<String> response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String transactionKeyOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(202);
        return readJson(response).get("transactionKey").textValue();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> recordComponentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList();
    }

}
