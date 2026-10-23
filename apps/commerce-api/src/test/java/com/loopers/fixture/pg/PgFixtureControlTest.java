package com.loopers.fixture.pg;

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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("테스트 PG fixture 는 typed 제어로 생성 결과·응답 장애를 재현한다.")
class PgFixtureControlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** gate 최대 대기(10초)보다 길어야 서버의 응답 종료를 클라이언트가 관찰한다. */
    private static final Duration GATE_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(20);
    private static final Duration OBSERVE_DEADLINE = Duration.ofSeconds(5);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final String CARD_NO = "0000-0000-0000-0000";

    private PgFixtureServer pg;
    private HttpClient client;
    private final List<CompletableFuture<?>> pending = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pg = PgFixtureServer.start();
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    /** 미완료 요청 취소 → client 유한 종료 → fixture 종료 확인. 앞 단계가 실패해도 뒤 단계를 수행하고 실패를 모두 남긴다. */
    @AfterEach
    void tearDown() throws Throwable {
        List<Throwable> failures = new ArrayList<>();
        pending.forEach(future -> future.cancel(true));
        try {
            client.shutdownNow();
            if (!client.awaitTermination(CLIENT_CLOSE_TIMEOUT)) {
                failures.add(new IllegalStateException("HttpClient 종료 미확인"));
            }
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            pg.close();
            assertThat(pg.isTerminated()).as("PG fixture HTTP worker 종료").isTrue();
        } catch (Throwable t) {
            failures.add(t);
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            failures.subList(1, failures.size()).forEach(first::addSuppressed);
            throw first;
        }
    }

    @DisplayName("생성 결과 plan 을 줄 때,")
    @Nested
    class CreateOutcomes {

        @DisplayName("SUCCESS 면 200 과 SUCCESS 거래를 돌려주고 같은 상태로 저장한다.")
        @Test
        void immediateSuccess() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000100", ResponsePlan.outcome(CreateOutcome.SUCCESS));

            HttpResponse<String> response = post("LP-ORD-0000000100", 7000);
            String key = readJson(response).get("transactionKey").textValue();

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(200),
                () -> assertThat(readJson(response).get("status").textValue()).isEqualTo("SUCCESS"),
                () -> assertThat(pg.state().transactions()).containsExactly(
                    new TransactionSnapshot(key, "LP-ORD-0000000100", 7000, PaymentStatus.SUCCESS)),
                () -> assertThat(readJson(get("/api/v1/payments/" + key)).get("status").textValue()).isEqualTo("SUCCESS")
            );
        }

        @DisplayName("REJECT_BEFORE_CREATE 면 거래 없이 정확한 422 본문을 돌려준다.")
        @Test
        void rejectsBeforeCreateWithoutEffect() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000101",
                ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));

            HttpResponse<String> response = post("LP-ORD-0000000101", 7000);
            awaitHandlersFinished();

            JsonNode body = readJson(response);
            JournalEntry entry = pg.state().journal().get(0);
            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(422),
                () -> assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json"),
                () -> assertThat(fieldNames(body)).containsExactly("code", "orderId", "amount", "transactionCreated"),
                () -> assertThat(body.get("code").textValue()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE"),
                () -> assertThat(body.get("orderId").textValue()).isEqualTo("LP-ORD-0000000101"),
                () -> assertThat(body.get("amount").isIntegralNumber()).isTrue(),
                () -> assertThat(body.get("amount").longValue()).isEqualTo(7000),
                () -> assertThat(body.get("transactionCreated").isBoolean()).isTrue(),
                () -> assertThat(body.get("transactionCreated").booleanValue()).isFalse(),
                () -> assertThat(pg.state().transactions()).isEmpty(),
                () -> assertThat(readJson(get("/api/v1/payments?orderId=LP-ORD-0000000101"))).isEmpty(),
                () -> assertThat(entry.responseStatus()).isEqualTo(422),
                () -> assertThat(entry.createdTransactionKey()).isNull()
            );
        }

        @DisplayName("같은 422 본문이라도 RAW 응답은 거래를 만든 뒤 응답만 바꾼 것이라 무효과 거절이 아니다.")
        @Test
        void rawErrorStillCreatesTransaction() {
            String rejectBody = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-0000000102\","
                + "\"amount\":7000,\"transactionCreated\":false}";
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000102",
                ResponsePlan.raw(422, rejectBody, Map.of("Content-Type", "application/json")));

            HttpResponse<String> response = post("LP-ORD-0000000102", 7000);

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(422),
                () -> assertThat(response.body()).isEqualTo(rejectBody),
                () -> assertThat(pg.state().transactions()).hasSize(1),
                () -> assertThat(pg.state().journal().get(0).createdTransactionKey())
                    .isEqualTo(pg.state().transactions().get(0).transactionKey())
            );
        }
    }

    @DisplayName("plan 을 등록·소비할 때,")
    @Nested
    class PlanQueue {

        @DisplayName("operation·대상별 FIFO 에서 다음 실제 요청이 하나씩 소비하고, 비면 기본 응답이다.")
        @Test
        void consumesPerTargetInOrder() {
            String key = transactionKeyOf(post("LP-ORD-0000000110", 7000));
            awaitHandlersFinished();
            FixtureState beforeEnqueue = pg.state();
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000111", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000111",
                ResponsePlan.outcome(CreateOutcome.REJECT_BEFORE_CREATE));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.raw(503, "", null));
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000110", ResponsePlan.raw(500, "x", null));
            FixtureState afterEnqueue = pg.state();

            List<Integer> statuses = List.of(
                post("LP-ORD-0000000112", 7000).statusCode(),
                post("LP-ORD-0000000111", 7000).statusCode(),
                post("LP-ORD-0000000111", 7000).statusCode(),
                post("LP-ORD-0000000111", 7000).statusCode(),
                get("/api/v1/payments/" + key).statusCode(),
                get("/api/v1/payments/" + key).statusCode(),
                get("/api/v1/payments?orderId=LP-ORD-0000000110").statusCode(),
                get("/api/v1/payments?orderId=LP-ORD-0000000110").statusCode());

            assertAll(
                () -> assertThat(afterEnqueue).as("등록만으로 거래·수신 기록 변화 없음").isEqualTo(beforeEnqueue),
                () -> assertThat(statuses).containsExactly(202, 200, 422, 202, 503, 200, 500, 200)
            );
        }

        @DisplayName("헤더·입력 오류로 거절한 요청은 plan 을 소비하지 않는다.")
        @Test
        void invalidRequestDoesNotConsumePlan() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000113", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            Map<String, Object> invalid = createFields("LP-ORD-0000000113", 7000);
            invalid.put("cardNo", "1111-1111-1111-1111");

            int rejected = send(createRequest(toJson(invalid))).statusCode();
            int accepted = post("LP-ORD-0000000113", 7000).statusCode();

            assertThat(List.of(rejected, accepted)).containsExactly(400, 200);
        }

        @DisplayName("같은 대상으로 동시에 10번 보내도 plan 하나는 정확히 한 요청만 소비한다.")
        @Test
        void consumesPlanExactlyOnceUnderConcurrency() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000114", ResponsePlan.outcome(CreateOutcome.SUCCESS));

            List<Integer> statuses = sendAll(IntStream.range(0, 10)
                .mapToObj(i -> createRequest(validBody("LP-ORD-0000000114", 7000)))
                .toList())
                .stream().map(HttpResponse::statusCode).toList();

            assertAll(
                () -> assertThat(statuses).filteredOn(status -> status == 200).hasSize(1),
                () -> assertThat(statuses).filteredOn(status -> status == 202).hasSize(9),
                () -> assertThat(pg.state().transactions()).hasSize(10)
            );
        }

        @DisplayName("잘못된 plan·조합은 등록 시 IllegalArgumentException 이고 아무것도 등록하지 않는다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.fixture.pg.PgFixtureControlTest#invalidPlans")
        void rejectsInvalidPlan(String description, PgOperation operation, String target, ResponsePlan plan) {
            FixtureState before = pg.state();

            assertThatThrownBy(() -> pg.enqueuePlan(operation, target, plan))
                .isInstanceOf(IllegalArgumentException.class);

            assertAll(
                () -> assertThat(pg.state()).isEqualTo(before),
                () -> assertThat(post("LP-ORD-0000000115", 7000).statusCode()).isEqualTo(202),
                () -> assertThat(pg.state().gates()).isEmpty()
            );
        }

        @DisplayName("같은 gateId 를 다시 등록하면 IllegalArgumentException 이고 그 plan 은 큐에 들어가지 않는다.")
        @Test
        void rejectsDuplicateGate() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000116", ResponsePlan.gate("g-dup"));

            assertThatThrownBy(() -> pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000116",
                ResponsePlan.gate("g-dup")))
                .isInstanceOf(IllegalArgumentException.class);

            assertAll(
                () -> assertThat(pg.state().gates()).containsExactly(new GateSnapshot("g-dup", GateStatus.REGISTERED)),
                () -> assertThat(get("/api/v1/payments?orderId=LP-ORD-0000000116").statusCode())
                    .as("거절된 GATE plan 이 없으므로 대기 없이 응답").isEqualTo(200)
            );
        }
    }

    @DisplayName("상태·gate 를 직접 제어할 때,")
    @Nested
    class Controls {

        @DisplayName("setStatus 는 거래 상태만 바꾸고 등록 plan·수신 기록은 그대로 둔다.")
        @Test
        void setStatusChangesOnlyTransaction() {
            String key = transactionKeyOf(post("LP-ORD-0000000120", 7000));
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.raw(500, "plan", null));
            awaitHandlersFinished();
            FixtureState before = pg.state();

            pg.setStatus(key, PaymentStatus.FAILED);

            FixtureState after = pg.state();
            HttpResponse<String> planned = get("/api/v1/payments/" + key);
            HttpResponse<String> normal = get("/api/v1/payments/" + key);
            assertAll(
                () -> assertThat(after.transactions()).containsExactly(
                    new TransactionSnapshot(key, "LP-ORD-0000000120", 7000, PaymentStatus.FAILED)),
                () -> assertThat(after.journal()).isEqualTo(before.journal()),
                () -> assertThat(after.counts()).isEqualTo(before.counts()),
                () -> assertThat(planned.body()).isEqualTo("plan"),
                () -> assertThat(readJson(normal).get("status").textValue()).isEqualTo("FAILED")
            );
        }

        @DisplayName("없는 거래·null 상태의 setStatus 와 없는 gate 해제는 IllegalArgumentException, gate 반복 해제는 성공한다.")
        @Test
        void rejectsInvalidControls() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000121", ResponsePlan.gate("g-1"));
            String key = transactionKeyOf(post("LP-ORD-0000000122", 7000));

            assertAll(
                () -> assertThatThrownBy(() -> pg.setStatus("no-such-key", PaymentStatus.SUCCESS))
                    .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> pg.setStatus(key, null)).isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> pg.releaseGate("no-such-gate")).isInstanceOf(IllegalArgumentException.class),
                () -> assertThatCode(() -> {
                    pg.releaseGate("g-1");
                    pg.releaseGate("g-1");
                }).doesNotThrowAnyException(),
                () -> assertThat(pg.state().gates()).containsExactly(new GateSnapshot("g-1", GateStatus.RELEASED))
            );
        }
    }

    @DisplayName("RAW 응답을 줄 때,")
    @Nested
    class RawReplies {

        @DisplayName("조회 계약과 다른 본문을 돌려줘도 저장 거래는 바뀌지 않는다.")
        @Test
        void rawLookupDoesNotChangeStore() {
            String key = transactionKeyOf(post("LP-ORD-0000000130", 7000));
            String forged = "{\"transactionKey\":\"other\",\"orderId\":\"LP-ORD-9999999999\",\"amount\":1,\"status\":\"SUCCESS\"}";
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.raw(200, forged, null));

            HttpResponse<String> raw = get("/api/v1/payments/" + key);
            HttpResponse<String> normal = get("/api/v1/payments/" + key);

            assertAll(
                () -> assertThat(raw.body()).isEqualTo(forged),
                () -> assertThat(pg.state().transactions()).containsExactly(
                    new TransactionSnapshot(key, "LP-ORD-0000000130", 7000, PaymentStatus.PENDING)),
                () -> assertThat(readJson(normal).get("amount").longValue()).isEqualTo(7000)
            );
        }

        @DisplayName("redirect 상태·Location header 를 그대로 보내며 클라이언트가 따라가지 않으면 대상 수신은 없다.")
        @Test
        void rawRedirect() {
            String location = pg.baseUrl() + "/api/v1/payments?orderId=LP-ORD-0000000132";
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000131",
                ResponsePlan.raw(302, "", Map.of("Location", location)));

            HttpResponse<String> response = get("/api/v1/payments?orderId=LP-ORD-0000000131");

            assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(302),
                () -> assertThat(response.headers().firstValue("Location")).hasValue(location),
                () -> assertThat(pg.state().journal()).extracting(JournalEntry::target)
                    .containsExactly("LP-ORD-0000000131")
            );
        }
    }

    @DisplayName("응답을 지연·유실시킬 때,")
    @Nested
    class LostResponses {

        @DisplayName("DELAY 가 클라이언트 timeout 보다 길어 응답을 못 받아도 CREATE 거래는 남고 조회된다.")
        @Test
        void delayedCreateKeepsTransaction() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000140", ResponsePlan.delay(1500));

            Throwable failure = sendExpectingFailure(
                createRequestBuilder(validBody("LP-ORD-0000000140", 7000)).timeout(Duration.ofMillis(300)).build());
            awaitHandlersFinished();

            FixtureState state = pg.state();
            JournalEntry entry = state.journal().get(0);
            HttpResponse<String> lookup = get("/api/v1/payments?orderId=LP-ORD-0000000140");
            assertAll(
                () -> assertThat(failure).isInstanceOf(HttpTimeoutException.class),
                () -> assertThat(state.transactions()).hasSize(1),
                () -> assertThat(entry.createdTransactionKey()).isEqualTo(state.transactions().get(0).transactionKey()),
                () -> assertThat(Duration.between(entry.receivedAt(), entry.completedAt()))
                    .as("header 전 실제 대기").isGreaterThanOrEqualTo(Duration.ofMillis(1500)),
                () -> assertThat(transactionKeys(readJson(lookup))).containsExactly(entry.createdTransactionKey())
            );
        }

        @DisplayName("CLOSE_EARLY 면 클라이언트는 I/O 오류를 받지만 CREATE 거래는 남고 거래 조회로 확인된다.")
        @Test
        void closeEarlyCreateKeepsTransaction() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000141", ResponsePlan.closeEarly());

            Throwable failure = sendExpectingFailure(createRequest(validBody("LP-ORD-0000000141", 7000)));
            awaitHandlersFinished();

            JournalEntry entry = pg.state().journal().get(0);
            HttpResponse<String> lookup = get("/api/v1/payments/" + entry.createdTransactionKey());
            System.out.println("[CLOSE_EARLY CREATE] client failure: " + describe(failure));
            assertAll(
                () -> assertThat(failure).isInstanceOf(IOException.class),
                () -> assertThat(entry.responseStatus()).isEqualTo(202),
                () -> assertThat(entry.failure()).isEqualTo(ResponseFailure.CLOSED_EARLY),
                () -> assertThat(lookup.statusCode()).isEqualTo(200),
                () -> assertThat(readJson(lookup).get("orderId").textValue()).isEqualTo("LP-ORD-0000000141")
            );
        }

        @DisplayName("조회 CLOSE_EARLY 는 응답만 끊고 거래 상태를 바꾸지 않는다.")
        @Test
        void closeEarlyLookupDoesNotChangeStore() {
            String key = transactionKeyOf(post("LP-ORD-0000000142", 7000));
            List<TransactionSnapshot> before = pg.state().transactions();
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.closeEarly());

            Throwable failure = sendExpectingFailure(getRequest("/api/v1/payments/" + key));
            System.out.println("[CLOSE_EARLY LOOKUP] client failure: " + describe(failure));

            assertAll(
                () -> assertThat(failure).isInstanceOf(IOException.class),
                () -> assertThat(pg.state().transactions()).isEqualTo(before),
                () -> assertThat(get("/api/v1/payments/" + key).statusCode()).isEqualTo(200)
            );
        }
    }

    @DisplayName("GATE 로 응답을 붙잡을 때,")
    @Nested
    class Gates {

        @DisplayName("거래를 만든 뒤 응답 전에 대기하고, 대기 중에도 다른 요청·관측이 가능하며 해제하면 응답한다.")
        @Test
        void holdsResponseUntilReleased() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000150", ResponsePlan.gate("g-150"));

            CompletableFuture<HttpResponse<String>> held = sendAsync(createRequest(validBody("LP-ORD-0000000150", 7000)));
            await().atMost(OBSERVE_DEADLINE).until(() -> gateStatus("g-150") == GateStatus.WAITING);

            FixtureState waiting = pg.state();
            HttpResponse<String> other = get("/api/v1/payments?orderId=LP-ORD-0000000150");
            boolean doneBeforeRelease = held.isDone();
            pg.releaseGate("g-150");
            HttpResponse<String> released = awaitResult(held);

            assertAll(
                () -> assertThat(waiting.transactions()).as("응답 전에 거래 효과").hasSize(1),
                () -> assertThat(waiting.activeRequests()).isEqualTo(1),
                () -> assertThat(transactionKeys(readJson(other)))
                    .containsExactly(waiting.transactions().get(0).transactionKey()),
                () -> assertThat(doneBeforeRelease).isFalse(),
                () -> assertThat(released.statusCode()).isEqualTo(202),
                () -> assertThat(gateStatus("g-150")).isEqualTo(GateStatus.RELEASED)
            );
        }

        @DisplayName("요청 전에 해제한 gate 는 기다리지 않는다.")
        @Test
        void passesPreReleasedGate() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000151", ResponsePlan.gate("g-151"));
            pg.releaseGate("g-151");

            assertThat(post("LP-ORD-0000000151", 7000).statusCode()).isEqualTo(202);
        }

        @DisplayName("10초 안에 해제하지 않으면 응답 없이 연결을 닫고 journal 에 GATE_TIMEOUT 을 남기며 거래는 보존한다.")
        @Test
        void expiresUnreleasedGate() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000152", ResponsePlan.gate("g-152"));

            long startedAt = System.nanoTime();
            Throwable failure = sendExpectingFailure(createRequestBuilder(validBody("LP-ORD-0000000152", 7000))
                .timeout(GATE_REQUEST_TIMEOUT).build());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            awaitHandlersFinished();
            pg.releaseGate("g-152");

            FixtureState state = pg.state();
            JournalEntry entry = state.journal().get(0);
            System.out.println("[GATE_TIMEOUT] elapsed=" + elapsed.toMillis() + "ms client failure: " + describe(failure));
            assertAll(
                () -> assertThat(failure).isInstanceOf(IOException.class)
                    .isNotInstanceOf(HttpTimeoutException.class),
                () -> assertThat(elapsed).isGreaterThanOrEqualTo(PaymentHttpHandler.GATE_TIMEOUT.minusMillis(100)),
                () -> assertThat(entry.failure()).isEqualTo(ResponseFailure.GATE_TIMEOUT),
                () -> assertThat(entry.responseStatus()).isNull(),
                () -> assertThat(state.gates()).containsExactly(new GateSnapshot("g-152", GateStatus.TIMED_OUT)),
                () -> assertThat(state.transactions()).hasSize(1)
            );
        }
    }

    @DisplayName("reset 할 때,")
    @Nested
    class Reset {

        @DisplayName("처리 중 요청이 있으면 거절하고 상태를 보존하며, 조용해지면 거래·plan·journal·gate 를 모두 지운다.")
        @Test
        void resetsOnlyWhenQuiet() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000161", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000161", ResponsePlan.outcome(CreateOutcome.SUCCESS));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000160", ResponsePlan.gate("g-160"));
            CompletableFuture<HttpResponse<String>> held = sendAsync(createRequest(validBody("LP-ORD-0000000160", 7000)));
            await().atMost(OBSERVE_DEADLINE).until(() -> gateStatus("g-160") == GateStatus.WAITING);
            FixtureState busy = pg.state();

            assertThatThrownBy(pg::reset).isInstanceOf(IllegalStateException.class);
            FixtureState afterRejectedReset = pg.state();
            pg.releaseGate("g-160");
            awaitResult(held);
            awaitHandlersFinished();
            int planPreserved = post("LP-ORD-0000000161", 7000).statusCode();
            awaitHandlersFinished();

            pg.reset();

            FixtureState cleared = pg.state();
            int planCleared = post("LP-ORD-0000000161", 7000).statusCode();
            assertAll(
                () -> assertThat(afterRejectedReset).isEqualTo(busy),
                () -> assertThat(planPreserved).isEqualTo(200),
                () -> assertThat(cleared).isEqualTo(new FixtureState(List.of(), new RequestCounts(0, 0, 0, 0), List.of(), 0, List.of(), List.of(), 0)),
                () -> assertThat(planCleared).as("남은 SUCCESS plan 도 지워짐").isEqualTo(202)
            );
        }
    }

    @DisplayName("close 할 때,")
    @Nested
    class Close {

        @DisplayName("gate·delay 대기 중이어도 유한 시간에 응답 없이 닫고 worker 를 종료하며, 이후 제어는 IllegalStateException 이다.")
        @Test
        void closesWhileWaiting() {
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000170", ResponsePlan.gate("g-170"));
            pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000171", ResponsePlan.delay(10_000));
            CompletableFuture<HttpResponse<String>> gated = sendAsync(
                createRequestBuilder(validBody("LP-ORD-0000000170", 7000)).timeout(GATE_REQUEST_TIMEOUT).build());
            CompletableFuture<HttpResponse<String>> delayed = sendAsync(
                createRequestBuilder(validBody("LP-ORD-0000000171", 7000)).timeout(GATE_REQUEST_TIMEOUT).build());
            await().atMost(OBSERVE_DEADLINE).until(() ->
                pg.state().activeRequests() == 2 && gateStatus("g-170") == GateStatus.WAITING);

            long startedAt = System.nanoTime();
            pg.close();
            Duration closeElapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            System.out.println("[CLOSE] elapsed=" + closeElapsed.toMillis() + "ms gated=" + describe(awaitFailure(gated))
                + " delayed=" + describe(awaitFailure(delayed)));
            assertAll(
                () -> assertThat(closeElapsed).isLessThan(Duration.ofSeconds(3)),
                () -> assertThat(pg.isTerminated()).isTrue(),
                () -> assertThat(liveThreadsNamedWith(pg.workerThreadNamePrefix())).isEmpty(),
                () -> assertThat(awaitFailure(gated)).isInstanceOf(IOException.class),
                () -> assertThat(awaitFailure(delayed)).isInstanceOf(IOException.class),
                () -> assertThatThrownBy(pg::state).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(pg::reset).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.releaseGate("g-170")).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.setStatus("any", PaymentStatus.SUCCESS))
                    .isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000172",
                    ResponsePlan.normal())).isInstanceOf(IllegalStateException.class),
                () -> assertThatCode(pg::close).doesNotThrowAnyException()
            );
        }

        @DisplayName("닫힌 fixture 의 제어 호출은 입력이 잘못됐어도 IllegalStateException 이다.")
        @Test
        void rejectsInvalidControlsAfterClose() {
            pg.close();

            assertAll(
                () -> assertThatThrownBy(() -> pg.setStatus(null, null)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.enqueuePlan(null, null, null)).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000173",
                    ResponsePlan.outcome(CreateOutcome.SUCCESS))).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000173",
                    ResponsePlan.delay(-1))).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(() -> pg.releaseGate(null)).isInstanceOf(IllegalStateException.class)
            );
        }
    }

    @DisplayName("journal 은 operation·대상·시각·응답 코드만 남기고 카드 값은 남기지 않으며, 제어·관측 호출은 세지 않는다.")
    @Test
    void journalsWithoutCardData() {
        String key = transactionKeyOf(post("LP-ORD-0000000180", 7000));
        get("/api/v1/payments/" + key);
        get("/api/v1/payments?orderId=LP-ORD-0000000180");
        pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000181", ResponsePlan.normal());
        pg.setStatus(key, PaymentStatus.SUCCESS);
        pg.state();
        awaitHandlersFinished();

        FixtureState state = pg.state();
        assertAll(
            () -> assertThat(state.counts()).isEqualTo(new RequestCounts(1, 1, 1, 0)),
            () -> assertThat(state.journal()).extracting(JournalEntry::operation, JournalEntry::target,
                    JournalEntry::responseStatus, JournalEntry::failure)
                .containsExactly(
                    tuple(PgOperation.CREATE, "LP-ORD-0000000180", 202, null),
                    tuple(PgOperation.LOOKUP_TRANSACTION, key, 200, null),
                    tuple(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000180", 200, null)),
            () -> assertThat(state.journal()).allSatisfy(entry ->
                assertThat(entry.completedAt()).isAfterOrEqualTo(entry.receivedAt())),
            () -> assertThat(state.toString()).doesNotContain(CARD_NO).as("저장 callbackUrl 비노출").doesNotContain("/api/v1/payments/callback")
        );
    }

    static Stream<Arguments> invalidPlans() {
        String target = "LP-ORD-0000000115";
        return Stream.of(
            Arguments.of("operation null", null, target, ResponsePlan.normal()),
            Arguments.of("target 빈 문자열", PgOperation.CREATE, "", ResponsePlan.normal()),
            Arguments.of("plan null", PgOperation.CREATE, target, null),
            Arguments.of("조회에 createOutcome", PgOperation.LOOKUP_ORDER, target, ResponsePlan.outcome(CreateOutcome.SUCCESS)),
            Arguments.of("REJECT + DELAY", PgOperation.CREATE, target,
                ResponsePlan.delay(10).withCreateOutcome(CreateOutcome.REJECT_BEFORE_CREATE)),
            Arguments.of("REJECT + RAW", PgOperation.CREATE, target,
                ResponsePlan.raw(422, "", null).withCreateOutcome(CreateOutcome.REJECT_BEFORE_CREATE)),
            Arguments.of("NORMAL 에 RAW 필드", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.NORMAL, null, null, 500, null, null)),
            Arguments.of("GATE 에 RAW body", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.GATE, null, "g-x", null, "x", null)),
            Arguments.of("NORMAL 에 delayMs", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.NORMAL, 10, null, null, null, null)),
            Arguments.of("DELAY 에 delayMs 없음", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.DELAY, null, null, null, null, null)),
            Arguments.of("DELAY -1ms", PgOperation.CREATE, target, ResponsePlan.delay(-1)),
            Arguments.of("DELAY 10001ms", PgOperation.CREATE, target, ResponsePlan.delay(10_001)),
            Arguments.of("GATE gateId 공백", PgOperation.CREATE, target, ResponsePlan.gate(" ")),
            Arguments.of("NORMAL 에 gateId", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.NORMAL, null, "g-y", null, null, null)),
            Arguments.of("RAW 상태 없음", PgOperation.CREATE, target,
                new ResponsePlan(null, ReplyMode.RAW, null, null, null, "", null)),
            Arguments.of("RAW 상태 199", PgOperation.CREATE, target, ResponsePlan.raw(199, "", null)),
            Arguments.of("RAW 상태 600", PgOperation.CREATE, target, ResponsePlan.raw(600, "", null)),
            Arguments.of("RAW body 없음", PgOperation.CREATE, target, ResponsePlan.raw(200, null, null)),
            Arguments.of("RAW 204 본문", PgOperation.CREATE, target, ResponsePlan.raw(204, "x", null)),
            Arguments.of("RAW Content-Length header", PgOperation.CREATE, target,
                ResponsePlan.raw(200, "x", Map.of("Content-Length", "100"))),
            Arguments.of("RAW header 줄바꿈", PgOperation.CREATE, target,
                ResponsePlan.raw(200, "x", Map.of("X-Test", "a\r\nb")))
        );
    }

    /**
     * 클라이언트가 응답을 받은 뒤에도 서버의 journal 완료 기록·처리 중 수 감소는 조금 늦게 끝난다.
     * 안정 상태 비교·reset·완료 기록 검증 직전에 진행 중 요청이 하나도 없음을 기한 안에 확인한다.
     * gate 로 요청을 붙잡아 둔 구간에서는 호출하지 않는다.
     */
    private void awaitHandlersFinished() {
        await().atMost(OBSERVE_DEADLINE).until(() -> {
            FixtureState state = pg.state();
            return state.activeRequests() == 0
                && state.journal().stream().allMatch(entry -> entry.completedAt() != null);
        });
    }

    private GateStatus gateStatus(String gateId) {
        return pg.state().gates().stream()
            .filter(gate -> gate.gateId().equals(gateId))
            .map(GateSnapshot::status)
            .findFirst()
            .orElse(null);
    }

    private static Map<String, Object> createFields(String orderId, long amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", orderId);
        fields.put("cardType", "TEST");
        fields.put("cardNo", CARD_NO);
        fields.put("amount", amount);
        fields.put("callbackUrl", "http://127.0.0.1:18080/api/v1/payments/callback");
        return fields;
    }

    private static String validBody(String orderId, long amount) {
        return toJson(createFields(orderId, amount));
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
            .header("X-USER-ID", "135135")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest createRequest(String body) {
        return createRequestBuilder(body).build();
    }

    private HttpRequest getRequest(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(pg.baseUrl() + pathAndQuery))
            .header("X-USER-ID", "135135")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    }

    private HttpResponse<String> post(String orderId, long amount) {
        return send(createRequest(validBody(orderId, amount)));
    }

    private HttpResponse<String> get(String pathAndQuery) {
        return send(getRequest(pathAndQuery));
    }

    private HttpResponse<String> send(HttpRequest request) {
        return awaitResult(sendAsync(request));
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending.add(future);
        return future;
    }

    /** 모든 요청을 RESULT_DEADLINE 안에 받는다. 실패·기한 초과면 미완료 요청을 취소한다. */
    private List<HttpResponse<String>> sendAll(List<HttpRequest> requests) {
        List<CompletableFuture<HttpResponse<String>>> futures = requests.stream().map(this::sendAsync).toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
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

    private static HttpResponse<String> awaitResult(CompletableFuture<HttpResponse<String>> future) {
        try {
            return future.get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("응답 대기 중단", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("요청 실패", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("응답 기한 초과", e);
        } finally {
            future.cancel(true);
        }
    }

    /** 요청이 실패하기를 기대한다. 응답을 받거나 기한을 넘기면 테스트 실패다. */
    private static Throwable awaitFailure(CompletableFuture<HttpResponse<String>> future) {
        try {
            HttpResponse<String> response = future.get(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError("응답을 받으면 안 됨: " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("응답 대기 중단", e);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new IllegalStateException("실패 대기 기한 초과", e);
        } finally {
            future.cancel(true);
        }
    }

    private Throwable sendExpectingFailure(HttpRequest request) {
        return awaitFailure(sendAsync(request));
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
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
