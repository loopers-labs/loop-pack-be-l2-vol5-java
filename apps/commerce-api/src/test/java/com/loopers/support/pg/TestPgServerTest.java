package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테스트 PG fixture 자체가 계약(docs/week5/pg-contract.md 3절)과 통제·관찰 약속(8절)대로 동작하는지 확인한다.
 * DB나 스프링 없이 JDK {@code HttpClient}로 직접 호출한다. 이 fixture로 만든 adapter·업무 테스트의 결과를 믿으려면
 * fixture의 응답 형식과 실패 장면이 먼저 믿을 만해야 한다.
 */
class TestPgServerTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7000L;
    private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofMillis(500))
        .build();

    private static TestPgServer pg;

    @BeforeAll
    static void startServer() {
        pg = TestPgServer.start();
    }

    @AfterAll
    static void stopServer() {
        pg.close();
    }

    @AfterEach
    void tearDown() {
        assertThat(pg.handlerErrors()).isEmpty();
        pg.reset();
    }

    @DisplayName("거래를 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("형식이 맞으면, 거래를 PENDING으로 저장하고 202와 계약의 타입대로 거래 객체를 돌려준다.")
        @Test
        void returns202AndStoresPendingTransaction() throws Exception {
            // act
            HttpResponse<String> response = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            JsonNode body = JSON.readTree(response.body());
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(body.get("transactionKey").isTextual()).isTrue();
            assertThat(body.get("transactionKey").asText()).matches("TEST-TX-\\d{4,}");
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("status").asText()).isEqualTo("PENDING");
            assertThat(pg.createCount()).isEqualTo(1);
            assertThat(pg.transactionsOf(ORDER_ID)).singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.transactionKey()).isEqualTo(body.get("transactionKey").asText());
                    assertThat(transaction.account()).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
                    assertThat(transaction.status()).isEqualTo(TestPgStatus.PENDING);
                });
        }

        @DisplayName("받은 요청의 메서드·경로·헤더·본문을 기록한다.")
        @Test
        void recordsReceivedRequest() throws Exception {
            // act
            post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            TestPgRequest request = pg.requests().get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(TestPgContract.PAYMENTS_PATH);
            assertThat(request.header("x-user-id")).isEqualTo(TestPgContract.PROVIDER_ACCOUNT);
            assertThat(request.header("Content-Type")).isEqualTo("application/json");
            assertThat(request.json().get("amount").isIntegralNumber()).isTrue();
            assertThat(request.json().get("cardNo").asText()).isEqualTo("0000-0000-0000-0000");
        }

        @DisplayName("같은 orderId로 다시 요청하면, 중복을 걸러 주지 않고 새 거래를 만든다.")
        @Test
        void createsNewTransaction_whenSameOrderIdIsSentAgain() throws Exception {
            // act
            HttpResponse<String> first = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> second = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(2);
            assertThat(JSON.readTree(first.body()).get("transactionKey").asText())
                .isNotEqualTo(JSON.readTree(second.body()).get("transactionKey").asText());
        }

        @DisplayName("필드·타입·헤더가 계약과 다르면, 거래를 만들지 않고 400으로 응답한다.")
        @Test
        void returns400WithoutTransaction_whenRequestBreaksContract() throws Exception {
            // arrange
            List<String> invalidBodies = List.of(
                createBody("SHORT", AMOUNT),                                    // orderId 6자 미만
                createBody(ORDER_ID, AMOUNT).replace("7000", "\"7000\""),        // amount가 문자열
                createBody(ORDER_ID, AMOUNT).replace("7000", "0"),               // amount가 0
                "{\"orderId\":"                                                // 깨진 JSON
            );

            // act
            for (String body : invalidBodies) {
                HttpResponse<String> response = post(body, TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
                assertThat(response.statusCode()).as(body).isEqualTo(400);
                assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo(TestPgContract.INVALID_REQUEST);
            }
            HttpResponse<String> withoutAccount = post(createBody(ORDER_ID, AMOUNT), null, NORMAL_TIMEOUT);

            // assert
            assertThat(withoutAccount.statusCode()).isEqualTo(400);
            assertThat(pg.transactionCount()).isZero();
            assertThat(pg.createCount()).isEqualTo(invalidBodies.size() + 1);
        }

        @DisplayName("확정 거절을 걸면, 거래를 저장하지 않고 계약의 422 본문으로 응답한다. 통제는 다음 요청 한 번에만 적용된다.")
        @Test
        void rejectsBeforeCreate_onceOnly() throws Exception {
            // arrange
            pg.rejectNextCreate();

            // act
            HttpResponse<String> rejected = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> next = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            JsonNode body = JSON.readTree(rejected.body());
            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(body.get("code").asText()).isEqualTo(TestPgContract.REJECTED_BEFORE_CREATE);
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("transactionCreated").isBoolean()).isTrue();
            assertThat(body.get("transactionCreated").asBoolean()).isFalse();
            assertThat(next.statusCode()).isEqualTo(202);
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
        }

        @DisplayName("임의 응답을 걸면, 정한 상태·본문으로 응답하고 거래 저장 여부도 정한 대로 따른다.")
        @Test
        void respondsAsConfigured() throws Exception {
            // arrange & act
            pg.respondNextCreate(500, "{\"code\":\"BOOM\"}", true);
            HttpResponse<String> createdButFailed = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            pg.respondNextCreate(422, null, false);
            HttpResponse<String> emptyRejection = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(createdButFailed.statusCode()).isEqualTo(500);
            assertThat(createdButFailed.body()).isEqualTo("{\"code\":\"BOOM\"}");
            assertThat(emptyRejection.statusCode()).isEqualTo(422);
            assertThat(emptyRejection.body()).isEmpty();
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
        }

        @DisplayName("응답을 붙잡으면, 거래는 저장되고 클라이언트는 timeout된다. 붙잡은 동안에도 다른 요청은 처리된다.")
        @Test
        void holdsResponse_whileOtherRequestsAreServed() throws Exception {
            // arrange
            pg.holdNextCreateResponse();

            // act
            assertThatThrownBy(() -> post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, SHORT_TIMEOUT))
                .isInstanceOf(HttpTimeoutException.class);
            TestPgTransaction held = pg.transactionsOf(ORDER_ID).get(0);
            HttpResponse<String> lookupWhileHeld = get(
                TestPgContract.PAYMENTS_PATH + "/" + held.transactionKey(), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(held.status()).isEqualTo(TestPgStatus.PENDING);
            assertThat(lookupWhileHeld.statusCode()).isEqualTo(200);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("응답을 끊으면, 거래는 저장되고 클라이언트는 응답 없이 끊긴 연결을 받는다. POST는 다시 오지 않는다.")
        @Test
        void dropsResponse_afterStoringTransaction() {
            // arrange
            pg.dropNextCreateResponse();

            // act & assert
            assertThatThrownBy(() -> post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);
            assertThat(pg.transactionsOf(ORDER_ID)).hasSize(1);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("다음 요청에 통제가 이미 걸려 있으면, 다른 통제를 겹쳐 걸 수 없다.")
        @Test
        void refusesSecondControl_whenOneIsPending() {
            // arrange
            pg.rejectNextCreate();

            // act & assert
            assertThatThrownBy(() -> pg.dropNextCreateResponse()).isInstanceOf(IllegalStateException.class);
        }
    }

    @DisplayName("거래를 조회할 때, ")
    @Nested
    class Lookup {

        @DisplayName("단건 조회는 같은 계정의 거래만 200으로 돌려주고, 없거나 다른 계정의 거래는 404다.")
        @Test
        void findsByKey_onlyWithinAccount() throws Exception {
            // arrange
            TestPgTransaction mine = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);
            TestPgTransaction others = pg.seedTransaction("999999", ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act
            HttpResponse<String> found = get(keyPath(mine), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> otherAccount = get(keyPath(others), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> missing = get(TestPgContract.PAYMENTS_PATH + "/TEST-TX-NONE", TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            JsonNode body = JSON.readTree(found.body());
            assertThat(found.statusCode()).isEqualTo(200);
            assertThat(body.get("transactionKey").asText()).isEqualTo(mine.transactionKey());
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(otherAccount.statusCode()).isEqualTo(404);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(JSON.readTree(missing.body()).get("code").asText()).isEqualTo(TestPgContract.NOT_FOUND);
            assertThat(pg.lookupByKeyCount()).isEqualTo(3);
        }

        @DisplayName("주문별 조회는 같은 계정의 거래를 생성 순서대로 배열로 돌려주고, 없으면 빈 배열이다.")
        @Test
        void listsByOrder_inCreationOrder() throws Exception {
            // arrange
            TestPgTransaction first = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.FAILED);
            TestPgTransaction second = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            pg.seedTransaction("999999", ORDER_ID, AMOUNT, TestPgStatus.SUCCESS);

            // act
            HttpResponse<String> both = get(orderPath(ORDER_ID), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> none = get(orderPath("LP-ORD-0000000099"), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            JsonNode list = JSON.readTree(both.body());
            assertThat(both.statusCode()).isEqualTo(200);
            assertThat(list.isArray()).isTrue();
            assertThat(list).extracting(node -> node.get("transactionKey").asText())
                .containsExactly(first.transactionKey(), second.transactionKey());
            assertThat(none.statusCode()).isEqualTo(200);
            assertThat(none.body()).isEqualTo("[]");
            assertThat(pg.lookupByOrderCount()).isEqualTo(2);
        }

        @DisplayName("주문별 조회의 query 값은 인코딩을 풀어 비교한다 (공백·&·+ 포함).")
        @Test
        void decodesQueryValue() throws Exception {
            // arrange
            pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, "A B&C+D", AMOUNT, TestPgStatus.PENDING);

            // act
            HttpResponse<String> response = get(
                TestPgContract.PAYMENTS_PATH + "?orderId=A%20B%26C%2BD", TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(JSON.readTree(response.body())).hasSize(1);
            assertThat(pg.requests().get(0).rawQuery()).isEqualTo("orderId=A%20B%26C%2BD");
        }

        @DisplayName("거래를 최종 상태로 바꾸면 조회와 결과 알림 본문에 반영되고, PENDING으로는 바꿀 수 없다.")
        @Test
        void completesTransaction_andBuildsCallbackPayload() throws Exception {
            // arrange
            TestPgTransaction transaction = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);

            // act
            pg.complete(transaction.transactionKey(), TestPgStatus.SUCCESS);
            HttpResponse<String> response = get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            JsonNode callback = JSON.readTree(pg.callbackPayload(transaction.transactionKey()));

            // assert
            assertThat(JSON.readTree(response.body()).get("status").asText()).isEqualTo("SUCCESS");
            assertThat(callback.get("transactionKey").asText()).isEqualTo(transaction.transactionKey());
            assertThat(callback.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(callback.get("status").asText()).isEqualTo("SUCCESS");
            assertThatThrownBy(() -> pg.complete(transaction.transactionKey(), TestPgStatus.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("조회 실패를 통제할 때, ")
    @Nested
    class LookupFailures {

        @DisplayName("정한 횟수만큼만 500으로 실패시키고, 그 뒤에는 정상 응답한다.")
        @Test
        void failsGivenNumberOfLookups() throws Exception {
            // arrange
            TestPgTransaction transaction = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            pg.failNextLookups(1, TestPgLookupFailure.SERVER_ERROR);

            // act
            HttpResponse<String> failed = get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> recovered = get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(JSON.readTree(failed.body()).get("code").asText()).isEqualTo(TestPgContract.SERVER_ERROR);
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(pg.lookupByKeyCount()).isEqualTo(2);
        }

        @DisplayName("계속 실패를 걸면 회복시킬 때까지 깨진 JSON을 돌려주고, 단건·주문별 조회에 똑같이 적용된다.")
        @Test
        void failsUntilHealed() throws Exception {
            // arrange
            TestPgTransaction transaction = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            pg.failLookups(TestPgLookupFailure.INVALID_JSON);

            // act
            HttpResponse<String> byKey = get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            HttpResponse<String> byOrder = get(orderPath(ORDER_ID), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            pg.healLookups();
            HttpResponse<String> healed = get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(byKey.statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> JSON.readTree(byKey.body())).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> JSON.readTree(byOrder.body())).isInstanceOf(IOException.class);
            assertThat(JSON.readTree(healed.body()).get("status").asText()).isEqualTo("PENDING");
        }

        @DisplayName("조회 응답을 붙잡으면 클라이언트가 timeout된다.")
        @Test
        void holdsLookup() {
            // arrange
            TestPgTransaction transaction = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            pg.failNextLookups(1, TestPgLookupFailure.HOLD);

            // act & assert
            assertThatThrownBy(() -> get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, SHORT_TIMEOUT))
                .isInstanceOf(HttpTimeoutException.class);
        }

        @DisplayName("조회 응답을 끊으면, JDK HttpClient가 GET을 한 번 다시 보내 서버는 조회를 두 번 받는다.")
        @Test
        void dropsLookup_andJdkClientResendsGetOnce() {
            // arrange
            TestPgTransaction transaction = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            pg.failLookups(TestPgLookupFailure.DROP);

            // act & assert
            assertThatThrownBy(() -> get(keyPath(transaction), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);
            assertThat(pg.lookupByKeyCount()).isEqualTo(2);
        }
    }

    @DisplayName("서버 수명을 다룰 때, ")
    @Nested
    class Lifecycle {

        @DisplayName("reset은 거래·호출 수·요청 기록·통제를 지우지만, 거래 키 일련번호는 이어진다.")
        @Test
        void resetClearsStateButKeepsKeySequence() throws Exception {
            // arrange
            TestPgTransaction before = pg.seedTransaction(TestPgContract.PROVIDER_ACCOUNT, ORDER_ID, AMOUNT, TestPgStatus.PENDING);
            get(keyPath(before), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);
            pg.rejectNextCreate();
            pg.failLookups(TestPgLookupFailure.SERVER_ERROR);

            // act
            pg.reset();
            HttpResponse<String> created = post(createBody(ORDER_ID, AMOUNT), TestPgContract.PROVIDER_ACCOUNT, NORMAL_TIMEOUT);

            // assert
            assertThat(created.statusCode()).isEqualTo(202);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.lookupByKeyCount()).isZero();
            assertThat(pg.requests()).hasSize(1);
            assertThat(JSON.readTree(created.body()).get("transactionKey").asText()).isNotEqualTo(before.transactionKey());
        }

        @DisplayName("close는 붙잡은 응답을 풀고 처리 스레드를 정리한 뒤 서버를 닫아, 이후 연결이 거절된다.")
        @Test
        void closeReleasesHeldResponsesAndStopsServer() {
            // arrange
            TestPgServer local = TestPgServer.start();
            String baseUrl = local.baseUrl();
            local.holdNextCreateResponse();
            assertThatThrownBy(() -> send(postRequest(baseUrl, createBody(ORDER_ID, AMOUNT), SHORT_TIMEOUT)))
                .isInstanceOf(HttpTimeoutException.class);

            // act
            long startedAt = System.nanoTime();
            local.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - startedAt);

            // assert: 붙잡힌 처리 스레드를 MAX_HOLD까지 기다리지 않고 풀어서 닫는다
            assertThat(closing).isLessThan(TestPgServer.MAX_HOLD);
            assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().startsWith(local.threadNamePrefix()) && thread.isAlive());
            assertThatThrownBy(() -> send(postRequest(baseUrl, createBody(ORDER_ID, AMOUNT), NORMAL_TIMEOUT)))
                .isInstanceOf(ConnectException.class);
        }

        @DisplayName("공유 서버는 JVM에 하나이고, 테스트가 닫을 수 없다.")
        @Test
        void sharedServerIsSingletonAndCannotBeClosed() {
            // act
            TestPgServer first = TestPgServer.shared();
            TestPgServer second = TestPgServer.shared();

            // assert
            assertThat(first).isSameAs(second);
            assertThat(first.baseUrl()).isNotEqualTo(pg.baseUrl());
            assertThatThrownBy(first::close).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static String createBody(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\",\"amount\":" + amount
            + ",\"callbackUrl\":\"http://127.0.0.1:8080/api/v1/payments/callback\"}";
    }

    private static String keyPath(TestPgTransaction transaction) {
        return TestPgContract.PAYMENTS_PATH + "/" + transaction.transactionKey();
    }

    private static String orderPath(String orderId) {
        return TestPgContract.PAYMENTS_PATH + "?orderId=" + orderId;
    }

    private static HttpResponse<String> post(String body, String account, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(pg.baseUrl() + TestPgContract.PAYMENTS_PATH))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (account != null) {
            builder.header(TestPgContract.ACCOUNT_HEADER, account);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String pathAndQuery, String account, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(pg.baseUrl() + pathAndQuery))
            .timeout(timeout)
            .header(TestPgContract.ACCOUNT_HEADER, account)
            .GET()
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest postRequest(String baseUrl, String body, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + TestPgContract.PAYMENTS_PATH))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header(TestPgContract.ACCOUNT_HEADER, TestPgContract.PROVIDER_ACCOUNT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
