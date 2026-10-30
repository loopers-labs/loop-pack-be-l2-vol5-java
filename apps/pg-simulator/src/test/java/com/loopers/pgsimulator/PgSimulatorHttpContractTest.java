package com.loopers.pgsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PG 시뮬레이터는 5.5 계약대로 실제 HTTP 로 거래를 만들고 조회한다.")
class PgSimulatorHttpContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final String CALLBACK_URL = "http://127.0.0.1:18080/api/v1/payments/callback";

    /** 정책 호출을 기록하고, 지정한 주문은 지정한 결과로, 나머지는 PENDING 으로 정한다. 자동 처리는 끈다. */
    private final List<CreateAttempt> attempts = new CopyOnWriteArrayList<>();
    private final Map<String, CreateOutcome> outcomes = new ConcurrentHashMap<>();
    private PgSimulator pg;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        pg = start();
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    private PgSimulator start() {
        return PgSimulator.start(new PgSimulatorOptions(0, attempt -> {
            attempts.add(attempt);
            return outcomes.getOrDefault(attempt.orderId(), CreateOutcome.PENDING_THEN_SUCCESS);
        }, Settlement.DISABLED));
    }

    /** client 정리가 실패해도 PG 를 닫고, 두 실패를 모두 남긴다. HttpClient.close() 는 종료까지 사실상 무기한 대기라 쓰지 않는다. */
    @AfterEach
    void tearDown() throws Throwable {
        Throwable failure = null;
        try {
            client.shutdownNow();
            if (!client.awaitTermination(CLIENT_CLOSE_TIMEOUT)) {
                throw new IllegalStateException("HttpClient 종료 미확인");
            }
        } catch (Throwable t) {
            failure = t;
        }
        try {
            pg.close();
            assertThat(pg.isTerminated()).as("PG worker 종료").isTrue();
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

    @DisplayName("거래를 생성할 때,")
    @Nested
    class Create {

        @DisplayName("PENDING 결과면 202 와 PENDING 거래 객체 4필드를 돌려주고 거래 한 건을 저장하며, 정책에는 주문·금액만 넘긴다.")
        @Test
        void returnsPendingTransaction() {
            HttpResponse<String> response = post(validCreateBody("LP-ORD-0000000042", 7000));

            JsonNode body = readJson(response);
            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(202),
                () -> assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json"),
                () -> assertThat(fieldNames(body)).containsExactlyInAnyOrder("transactionKey", "orderId", "amount", "status"),
                () -> assertThat(body.get("transactionKey").textValue()).matches("[A-Za-z0-9_-]{1,255}"),
                () -> assertThat(body.get("orderId").textValue()).isEqualTo("LP-ORD-0000000042"),
                () -> assertThat(body.get("amount").isIntegralNumber()).isTrue(),
                () -> assertThat(body.get("amount").longValue()).isEqualTo(7000L),
                () -> assertThat(body.get("status").textValue()).isEqualTo("PENDING"),
                () -> assertThat(pg.transactions().findAll()).containsExactly(new TransactionSnapshot(
                    body.get("transactionKey").textValue(), "LP-ORD-0000000042", 7000L, PaymentStatus.PENDING, CALLBACK_URL)),
                () -> assertThat(attempts).containsExactly(new CreateAttempt("LP-ORD-0000000042", 7000L))
            );
        }

        @DisplayName("즉시 성공 결과면 200 과 SUCCESS 거래를 돌려주고 SUCCESS 로 저장한다.")
        @Test
        void returnsImmediateSuccess() {
            outcomes.put("LP-ORD-0000000046", CreateOutcome.IMMEDIATE_SUCCESS);

            HttpResponse<String> response = post(validCreateBody("LP-ORD-0000000046", 7000));

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(200),
                () -> assertThat(readJson(response).get("status").textValue()).isEqualTo("SUCCESS"),
                () -> assertThat(pg.transactions().findAll()).singleElement()
                    .extracting(TransactionSnapshot::status).isEqualTo(PaymentStatus.SUCCESS)
            );
        }

        @DisplayName("생성 전 거절 결과면 정확한 422 본문을 돌려주고 거래를 만들지 않는다.")
        @Test
        void rejectsBeforeCreate() {
            outcomes.put("LP-ORD-0000000047", CreateOutcome.REJECT_BEFORE_CREATE);

            HttpResponse<String> response = post(validCreateBody("LP-ORD-0000000047", 7000));

            JsonNode body = readJson(response);
            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(422),
                () -> assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json"),
                () -> assertThat(fieldNames(body)).containsExactly("code", "orderId", "amount", "transactionCreated"),
                () -> assertThat(body.get("code").textValue()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE"),
                () -> assertThat(body.get("orderId").textValue()).isEqualTo("LP-ORD-0000000047"),
                () -> assertThat(body.get("amount").longValue()).isEqualTo(7000L),
                () -> assertThat(body.get("transactionCreated").isBoolean()).isTrue(),
                () -> assertThat(body.get("transactionCreated").booleanValue()).isFalse(),
                () -> assertThat(pg.transactions().findAll()).isEmpty()
            );
        }

        @DisplayName("같은 orderId 로 두 번 보내면 멱등 처리하지 않고 다른 거래 키의 거래 두 건을 만든다.")
        @Test
        void createsSeparateTransactionsForSameOrder() {
            String first = transactionKeyOf(post(validCreateBody("LP-ORD-0000000043", 7000)));
            String second = transactionKeyOf(post(validCreateBody("LP-ORD-0000000043", 7000)));

            assertAll(
                () -> assertThat(first).isNotEqualTo(second),
                () -> assertThat(pg.transactions().findAll())
                    .extracting(TransactionSnapshot::transactionKey)
                    .containsExactly(first, second)
            );
        }

        @DisplayName("같은 orderId 로 동시에 32번 보내도 요청마다 서로 다른 거래를 하나씩 저장한다.")
        @Test
        void storesEveryConcurrentCreate() {
            int requests = 32;

            List<String> keys = sendAllWithin(BATCH_TIMEOUT, IntStream.range(0, requests)
                .mapToObj(i -> createRequest(validCreateBody("LP-ORD-0000000044", 7000)))
                .toList())
                .stream()
                .map(PgSimulatorHttpContractTest::transactionKeyOf)
                .toList();

            assertAll(
                () -> assertThat(keys).doesNotHaveDuplicates().hasSize(requests),
                () -> assertThat(pg.transactions().findAll())
                    .extracting(TransactionSnapshot::transactionKey)
                    .containsExactlyInAnyOrderElementsOf(keys)
            );
        }

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
                // 헤더를 받은 요청이 worker 에 넘어가면 첫 HTTP worker 가 생긴다.
                await().atMost(REQUEST_TIMEOUT).until(() -> liveThreadsNamedWith(pg.httpThreadNamePrefix()).size() == 1);

                HttpResponse<String> created = post(validCreateBody("LP-ORD-0000000045", 7000));
                HttpResponse<String> lookedUp = get("/api/v1/payments?orderId=LP-ORD-0000000045");

                assertAll(
                    () -> assertThat(created.statusCode()).isEqualTo(202),
                    () -> assertThat(lookedUp.statusCode()).isEqualTo(200),
                    () -> assertThat(readJson(lookedUp)).hasSize(1)
                );
            }
            assertThat(pg.transactions().findAll())
                .extracting(TransactionSnapshot::orderId)
                .containsExactly("LP-ORD-0000000045");
        }
    }

    @DisplayName("거래를 조회할 때,")
    @Nested
    class Lookup {

        @DisplayName("거래 키로 조회하면 200 과 생성 응답과 같은 거래 객체를 돌려준다.")
        @Test
        void returnsTransactionByKey() {
            HttpResponse<String> created = post(validCreateBody("LP-ORD-0000000050", 12000));
            String key = transactionKeyOf(created);

            HttpResponse<String> response = get("/api/v1/payments/" + key);

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(200),
                () -> assertThat(readJson(response)).isEqualTo(readJson(created))
            );
        }

        @DisplayName("상태를 바꾸면 조회 응답에 바뀐 상태가 보인다.")
        @Test
        void returnsChangedStatus() {
            String key = transactionKeyOf(post(validCreateBody("LP-ORD-0000000055", 7000)));

            pg.transactions().setStatus(key, PaymentStatus.FAILED);

            assertThat(readJson(get("/api/v1/payments/" + key)).get("status").textValue()).isEqualTo("FAILED");
        }

        @DisplayName("없는 거래 키나 형식이 다른 키로 조회하면 404 이고 거래는 생기지 않는다.")
        @Test
        void returnsNotFoundForUnknownKey() {
            post(validCreateBody("LP-ORD-0000000051", 7000));

            HttpResponse<String> unknown = get("/api/v1/payments/" + UUID.randomUUID());
            HttpResponse<String> malformed = get("/api/v1/payments/" + URLEncoder.encode("a b", StandardCharsets.UTF_8));

            assertAll(
                () -> assertThat(unknown.statusCode()).isEqualTo(404),
                () -> assertThat(malformed.statusCode()).isEqualTo(404),
                () -> assertThat(pg.transactions().findAll()).hasSize(1)
            );
        }

        @DisplayName("주문별로 조회하면 거래가 없을 때 빈 배열, 있으면 그 주문의 거래만 생성 순서로 1건·복수 건 돌려준다.")
        @Test
        void returnsTransactionsByOrder() {
            HttpResponse<String> none = get("/api/v1/payments?orderId=LP-ORD-0000000052");
            String single = transactionKeyOf(post(validCreateBody("LP-ORD-0000000053", 7000)));
            String first = transactionKeyOf(post(validCreateBody("LP-ORD-0000000054", 8000)));
            String second = transactionKeyOf(post(validCreateBody("LP-ORD-0000000054", 8000)));

            HttpResponse<String> one = get("/api/v1/payments?orderId=LP-ORD-0000000053");
            HttpResponse<String> many = get("/api/v1/payments?orderId=LP-ORD-0000000054");

            assertAll(
                () -> assertThat(none.statusCode()).isEqualTo(200),
                () -> assertThat(readJson(none).isArray()).isTrue(),
                () -> assertThat(readJson(none)).isEmpty(),
                () -> assertThat(one.statusCode()).isEqualTo(200),
                () -> assertThat(transactionKeys(readJson(one))).containsExactly(single),
                () -> assertThat(many.statusCode()).isEqualTo(200),
                () -> assertThat(transactionKeys(readJson(many))).containsExactly(first, second),
                () -> assertThat(fieldNames(readJson(many).get(0)))
                    .containsExactlyInAnyOrder("transactionKey", "orderId", "amount", "status")
            );
        }

        @DisplayName("인코딩한 주문 ID 로 조회하면 생성 때와 같은 문자열의 거래를 찾는다.")
        @Test
        void decodesEncodedOrderId() {
            String orderId = "주문 #A&B=55+";
            String key = transactionKeyOf(post(validCreateBody(orderId, 7000)));

            HttpResponse<String> response = get(
                "/api/v1/payments?orderId=" + URLEncoder.encode(orderId, StandardCharsets.UTF_8));

            assertThat(transactionKeys(readJson(response))).containsExactly(key);
        }

        @DisplayName("orderId query 가 없거나 비었거나 여러 개면 400 이다.")
        @Test
        void rejectsInvalidOrderQuery() {
            assertAll(
                () -> assertThat(get("/api/v1/payments").statusCode()).isEqualTo(400),
                () -> assertThat(get("/api/v1/payments?orderId=").statusCode()).isEqualTo(400),
                () -> assertThat(get("/api/v1/payments?orderId=LP-ORD-1&orderId=LP-ORD-2").statusCode()).isEqualTo(400)
            );
        }
    }

    @DisplayName("요청을 거절할 때,")
    @Nested
    class Reject {

        @DisplayName("X-USER-ID 가 없거나 다르거나 여러 개면 세 요청 모두 400 이고 정책을 호출하지 않으며 거래를 만들지 않는다.")
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
                () -> assertThat(pg.transactions().findAll())
                    .extracting(TransactionSnapshot::transactionKey)
                    .containsExactly(key),
                () -> assertThat(attempts).as("헤더 거절은 정책 호출 없음").hasSize(1)
            );
        }

        @DisplayName("생성 입력이 계약과 다르면 400 이고 정책을 호출하지 않으며 거래를 만들지 않고 응답에 카드 번호를 남기지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.pgsimulator.PgSimulatorHttpContractTest#invalidCreateBodies")
        void rejectsInvalidCreateBody(String description, String body) {
            HttpResponse<String> response = post(body);

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(400),
                () -> assertThat(response.body()).doesNotContain(CARD_NO).doesNotContain("1111-1111-1111-1111"),
                () -> assertThat(pg.transactions().findAll()).isEmpty(),
                () -> assertThat(attempts).isEmpty()
            );
        }

        @DisplayName("계약에 없는 경로는 404, 지원하지 않는 메서드는 405 이며 거래를 만들지 않는다.")
        @Test
        void rejectsUnknownRouteAndMethod() {
            HttpResponse<String> unknownPath = get("/api/v1/payments/a/b");
            HttpResponse<String> deleteOrder = send(
                HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments")).DELETE(), withProviderUser());
            HttpResponse<String> postKey = send(HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments/abc"))
                .POST(HttpRequest.BodyPublishers.ofString(validCreateBody("LP-ORD-0000000062", 7000))), withProviderUser());

            assertAll(
                () -> assertThat(unknownPath.statusCode()).isEqualTo(404),
                () -> assertThat(deleteOrder.statusCode()).isEqualTo(405),
                () -> assertThat(postKey.statusCode()).isEqualTo(405),
                () -> assertThat(pg.transactions().findAll()).isEmpty(),
                () -> assertThat(attempts).isEmpty()
            );
        }
    }

    @DisplayName("수명을 관리할 때,")
    @Nested
    class Lifecycle {

        @DisplayName("동시에 띄운 두 PG 는 포트·거래를 공유하지 않는다.")
        @Test
        void isolatesStateBetweenSimulators() {
            PgSimulator other = start();
            try {
                String key = transactionKeyOf(post(validCreateBody("LP-ORD-0000000070", 7000)));

                HttpResponse<String> otherLookup = send(
                    HttpRequest.newBuilder(URI.create(other.baseUrl() + "/api/v1/payments/" + key)).GET(), withProviderUser());

                assertAll(
                    () -> assertThat(other.baseUrl()).isNotEqualTo(pg.baseUrl()),
                    () -> assertThat(otherLookup.statusCode()).isEqualTo(404),
                    () -> assertThat(other.transactions().findAll()).isEmpty()
                );
            } finally {
                other.close();
            }
            assertThat(other.isTerminated()).isTrue();
        }

        @DisplayName("저장소를 비우면 이전 거래가 조회되지 않는다.")
        @Test
        void clearsTransactions() {
            String key = transactionKeyOf(post(validCreateBody("LP-ORD-0000000071", 7000)));

            pg.transactions().clear();

            assertAll(
                () -> assertThat(pg.transactions().findAll()).isEmpty(),
                () -> assertThat(get("/api/v1/payments/" + key).statusCode()).isEqualTo(404),
                () -> assertThat(readJson(get("/api/v1/payments?orderId=LP-ORD-0000000071"))).isEmpty()
            );
        }

        @DisplayName("close 하면 연결을 거절하고 HTTP worker 가 모두 종료되며, 반복 호출해도 안전하다.")
        @Test
        void closesServerAndWorkers() {
            sendAllWithin(BATCH_TIMEOUT, IntStream.range(0, PgSimulator.HTTP_WORKERS)
                .mapToObj(i -> createRequest(validCreateBody("LP-ORD-0000000072", 7000)))
                .toList())
                .forEach(PgSimulatorHttpContractTest::transactionKeyOf);
            String baseUrl = pg.baseUrl();
            assertThat(liveThreadsNamedWith(pg.httpThreadNamePrefix())).as("close 전 worker 존재").isNotEmpty();

            pg.close();
            pg.close();

            assertAll(
                () -> assertThat(pg.isTerminated()).isTrue(),
                () -> assertThat(liveThreadsNamedWith(pg.httpThreadNamePrefix())).isEmpty(),
                () -> assertThatThrownBy(() -> client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/payments?orderId=LP-ORD-0000000072"))
                        .header("X-USER-ID", "135135").timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(IOException.class)
            );
        }
    }

    @DisplayName("저장 타입은 카드 정보와 요청 원문 필드를 갖지 않는다.")
    @Test
    void storesOnlyContractFields() {
        assertThat(Arrays.stream(TransactionSnapshot.class.getRecordComponents()).map(component -> component.getName()))
            .containsExactly("transactionKey", "orderId", "amount", "status", "callbackUrl");
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
            Arguments.of("orderId 5자", withField("orderId", "ORD-1")),
            Arguments.of("orderId 숫자 타입", withField("orderId", 4200000000L)),
            Arguments.of("orderId 누락", withoutField("orderId")),
            Arguments.of("orderId null", withField("orderId", null)),
            Arguments.of("cardType 다른 값", withField("cardType", "VISA")),
            Arguments.of("cardType 누락", withoutField("cardType")),
            Arguments.of("cardNo 다른 값", withField("cardNo", "1111-1111-1111-1111")),
            Arguments.of("cardNo 숫자 타입", withField("cardNo", 0L)),
            Arguments.of("cardNo 누락", withoutField("cardNo")),
            Arguments.of("amount 문자열", withField("amount", "7000")),
            Arguments.of("amount 소수", withField("amount", 7000.5)),
            Arguments.of("amount 소수점 표기 정수", withField("amount", 7000.0)),
            Arguments.of("amount 0", withField("amount", 0)),
            Arguments.of("amount 음수", withField("amount", -1)),
            Arguments.of("amount long 범위 초과", validCreateBody("LP-ORD-0000000080", 7000)
                .replace("\"amount\":7000", "\"amount\":92233720368547758070")),
            Arguments.of("amount 누락", withoutField("amount")),
            Arguments.of("callbackUrl https", withField("callbackUrl", "https://127.0.0.1:18080/api/v1/payments/callback")),
            Arguments.of("callbackUrl localhost", withField("callbackUrl", "http://localhost:18080/api/v1/payments/callback")),
            Arguments.of("callbackUrl 외부 주소", withField("callbackUrl", "http://10.0.0.1:18080/api/v1/payments/callback")),
            Arguments.of("callbackUrl 포트 생략", withField("callbackUrl", "http://127.0.0.1/api/v1/payments/callback")),
            Arguments.of("callbackUrl 포트 0", withField("callbackUrl", "http://127.0.0.1:0/api/v1/payments/callback")),
            Arguments.of("callbackUrl 다른 경로", withField("callbackUrl", "http://127.0.0.1:18080/api/v1/payments")),
            Arguments.of("callbackUrl query", withField("callbackUrl", CALLBACK_URL + "?redirect=1")),
            Arguments.of("callbackUrl 사용자 정보", withField("callbackUrl", "http://u@127.0.0.1:18080/api/v1/payments/callback")),
            Arguments.of("callbackUrl 숫자 타입", withField("callbackUrl", 1L)),
            Arguments.of("callbackUrl 누락", withoutField("callbackUrl")),
            Arguments.of("JSON 형식 오류", "{\"orderId\":"),
            Arguments.of("JSON 배열", "[" + validCreateBody("LP-ORD-0000000081", 7000) + "]"),
            Arguments.of("빈 본문", ""),
            Arguments.of("중복 필드", validCreateBody("LP-ORD-0000000082", 7000)
                .replace("{", "{\"amount\":1,")),
            Arguments.of("JSON 뒤 추가 값", validCreateBody("LP-ORD-0000000083", 7000) + "{}")
        );
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

    private static String withField(String name, Object value) {
        Map<String, Object> fields = validCreateFields("LP-ORD-0000000090", 7000);
        fields.put(name, value);
        return toJson(fields);
    }

    private static String withoutField(String name) {
        Map<String, Object> fields = validCreateFields("LP-ORD-0000000091", 7000);
        fields.remove(name);
        return toJson(fields);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpRequest createRequest(String body) {
        HttpRequest.Builder builder = createRequestBuilder(body).timeout(REQUEST_TIMEOUT);
        withProviderUser().accept(builder);
        return builder.build();
    }

    /** 전체 기한 안에 끝나지 않거나 하나라도 실패하면 미완료 요청을 취소하고 실패시킨다. */
    private List<HttpResponse<String>> sendAllWithin(Duration timeout, List<HttpRequest> requests) {
        List<CompletableFuture<HttpResponse<String>>> futures = requests.stream()
            .map(request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("비동기 요청 대기 중단", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("비동기 요청 실패 또는 기한 초과", e);
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
        return futures.stream().map(CompletableFuture::join).toList(); // allOf 완료 뒤라 대기하지 않음
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

    private static List<String> transactionKeys(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(transaction -> keys.add(transaction.get("transactionKey").textValue()));
        return keys;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<Thread> liveThreadsNamedWith(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(prefix) && thread.isAlive())
            .toList();
    }
}
