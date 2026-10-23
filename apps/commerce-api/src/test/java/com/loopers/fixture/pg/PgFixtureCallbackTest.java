package com.loopers.fixture.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 명시적 콜백 전송의 fixture 자체 계약. 수신 측은 로컬 수신 대역이므로 ACK 는 커머스 업무 완료가 아니고,
 * count≥2 의 중복 전송도 커머스의 동시 적용을 입증하지 않는다.
 */
@DisplayName("테스트 PG fixture 는 명시한 횟수만큼 콜백을 보내고 모든 전송 결과를 모아 완료한다.")
class PgFixtureCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BATCH_DEADLINE = Duration.ofSeconds(10);
    private static final Duration OBSERVE_DEADLINE = Duration.ofSeconds(5);
    /** 콜백 client 응답 timeout(1초) 안에 hold 를 풀어야 하므로 관찰 간격을 짧게 둔다. */
    private static final Duration FAST_POLL = Duration.ofMillis(5);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private PgFixtureServer pg;
    private CallbackReceiverStub receiver;
    private HttpClient client;
    private final List<CompletableFuture<?>> pending = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        pg = PgFixtureServer.start();
        receiver = CallbackReceiverStub.start();
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    /** 미완료 대기 취소 → client 유한 종료 → 수신 대역 종료 → fixture 종료 확인. 앞 단계가 실패해도 모두 수행한다. */
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
            receiver.close();
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            pg.close();
            assertThat(pg.isTerminated()).as("PG fixture HTTP·콜백 worker 종료").isTrue();
        } catch (Throwable t) {
            failures.add(t);
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            failures.subList(1, failures.size()).forEach(first::addSuppressed);
            throw first;
        }
    }

    @DisplayName("생성·상태 변경은 콜백을 보내지 않고, 명시 전송은 저장 URL 로 정확한 알림 본문을 보내며 거래를 바꾸지 않는다.")
    @Test
    void sendsStoredSnapshotToStoredUrl() {
        String key = createTransaction("LP-ORD-0000000200", receiver.callbackUrl());
        pg.setStatus(key, PaymentStatus.SUCCESS);
        FixtureState beforeSend = pg.state();

        CallbackBatchResult result = awaitBatch(pg.sendCallbacks(key, 1));

        FixtureState afterSend = pg.state();
        CallbackReceiverStub.ReceivedCallback received = receiver.received().get(0);
        JsonNode body = readJson(received.body());
        assertAll(
            () -> assertThat(beforeSend.counts().callbackSent()).as("생성·setStatus 뒤 자동 콜백 없음").isZero(),
            () -> assertThat(beforeSend.callbacks()).isEmpty(),
            () -> assertThat(result).isEqualTo(new CallbackBatchResult(key,
                List.of(new CallbackDelivery(1, true, 200, null, null)))),
            () -> assertThat(receiver.received()).hasSize(1),
            () -> assertThat(received.method()).isEqualTo("POST"),
            () -> assertThat(received.path()).isEqualTo(CallbackReceiverStub.CALLBACK_PATH),
            () -> assertThat(received.contentType()).startsWith("application/json"),
            () -> assertThat(fieldNames(body)).containsExactly("providerOrderId", "transactionKey", "status"),
            () -> assertThat(body.get("providerOrderId").textValue()).isEqualTo("LP-ORD-0000000200"),
            () -> assertThat(body.get("transactionKey").textValue()).isEqualTo(key),
            () -> assertThat(body.get("status").textValue()).isEqualTo("SUCCESS"),
            () -> assertThat(afterSend.transactions()).isEqualTo(beforeSend.transactions()),
            () -> assertThat(afterSend.journal()).as("콜백은 PG 수신이 아님").isEqualTo(beforeSend.journal()),
            () -> assertThat(afterSend.counts()).isEqualTo(new RequestCounts(1, 0, 0, 1)),
            () -> assertThat(afterSend.callbacks()).singleElement().satisfies(record -> {
                assertThat(record.status()).isEqualTo(PaymentStatus.SUCCESS);
                assertThat(record.httpStatus()).isEqualTo(200);
                assertThat(record.completedAt()).isAfterOrEqualTo(record.startedAt());
            }),
            () -> assertThat(afterSend.activeCallbackJobs()).isZero(),
            () -> assertThat(afterSend.toString()).as("관측에 callbackUrl 비노출").doesNotContain(receiver.callbackUrl())
        );
    }

    @DisplayName("count 만큼 호출 시점 snapshot 을 보내며, 대기 중에도 PG 조회·관측이 되고 reset 은 거절되며, 모든 결과가 모여야 완료된다.")
    @Test
    void sendsDuplicatesFromCallTimeSnapshot() {
        String key = createTransaction("LP-ORD-0000000201", receiver.callbackUrl());
        pg.setStatus(key, PaymentStatus.SUCCESS);
        receiver.hold();

        CompletableFuture<CallbackBatchResult> batch = track(pg.sendCallbacks(key, 3));
        await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> receiver.inFlight() == 2);
        FixtureState holding = pg.state();
        long liveCallbackThreads = liveThreadsNamedWith(pg.callbackThreadNamePrefix());
        HttpResponse<String> lookup = get("/api/v1/payments/" + key);
        pg.setStatus(key, PaymentStatus.FAILED);
        awaitHandlersFinished();
        FixtureState beforeReset = pg.state();
        Throwable resetFailure = catchThrowable(pg::reset);
        FixtureState afterRejectedReset = pg.state();
        boolean doneWhileHolding = batch.isDone();
        receiver.release();

        CallbackBatchResult result = awaitBatch(batch);
        FixtureState afterBatch = pg.state();
        pg.reset();
        FixtureState cleared = pg.state();

        assertAll(
            () -> assertThat(holding.counts().callbackSent()).as("실제 전송 시작 2건").isEqualTo(2),
            () -> assertThat(holding.activeCallbackJobs()).as("접수 3건(대기 1 포함)").isEqualTo(3),
            () -> assertThat(liveCallbackThreads).as("콜백 전용 worker 2개").isEqualTo(CallbackSender.WORKERS),
            () -> assertThat(lookup.statusCode()).as("콜백 대기 중 PG 조회").isEqualTo(200),
            () -> assertThat(resetFailure).isInstanceOf(IllegalStateException.class),
            () -> assertThat(afterRejectedReset).isEqualTo(beforeReset),
            () -> assertThat(doneWhileHolding).isFalse(),
            () -> assertThat(result.deliveries()).extracting(CallbackDelivery::attempt).containsExactly(1, 2, 3),
            () -> assertThat(result.deliveries()).extracting(CallbackDelivery::httpStatus).containsOnly(200),
            () -> assertThat(receiver.received()).hasSize(3)
                .allSatisfy(received -> assertThat(readJson(received.body()).get("status").textValue())
                    .as("호출 시점 snapshot").isEqualTo("SUCCESS")),
            () -> assertThat(afterBatch.transactions().get(0).status()).as("전송은 상태를 바꾸지 않음")
                .isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(afterBatch.counts().callbackSent()).isEqualTo(3),
            () -> assertThat(afterBatch.activeCallbackJobs()).as("batch 완료 시점에 작업 종료").isZero(),
            () -> assertThat(cleared.callbacks()).isEmpty(),
            () -> assertThat(cleared.counts()).isEqualTo(new RequestCounts(0, 0, 0, 0))
        );
    }

    @DisplayName("202·409·500 응답을 각 전송 결과로 보존하고 응답 코드를 이유로 다시 보내지 않는다.")
    @Test
    void preservesResponsesWithoutResend() {
        String key = createTransaction("LP-ORD-0000000202", receiver.callbackUrl());
        receiver.replyWith(new CallbackReceiverStub.Reply(202, Map.of()), new CallbackReceiverStub.Reply(409, Map.of()),
            new CallbackReceiverStub.Reply(500, Map.of()));

        CallbackBatchResult result = awaitBatch(pg.sendCallbacks(key, 3));

        assertAll(
            () -> assertThat(result.deliveries()).extracting(CallbackDelivery::httpStatus)
                .containsExactlyInAnyOrder(202, 409, 500),
            () -> assertThat(result.deliveries()).extracting(CallbackDelivery::failure).containsOnlyNulls(),
            () -> assertThat(receiver.received()).hasSize(3),
            () -> assertThat(pg.state().counts().callbackSent()).isEqualTo(3)
        );
    }

    @DisplayName("연결할 수 없는 주소면 통신 오류를 결과로 남기고 요청한 횟수 외에 다시 보내지 않는다.")
    @Test
    void recordsConnectionFailureWithoutResend() throws IOException {
        String key = createTransaction("LP-ORD-0000000203", "http://127.0.0.1:" + closedPort() + "/api/v1/payments/callback");

        CallbackBatchResult result = awaitBatch(pg.sendCallbacks(key, 2));

        System.out.println("[CALLBACK CONNECT] " + result.deliveries().get(0).errorDetail());
        assertAll(
            () -> assertThat(result.deliveries()).hasSize(2).allSatisfy(delivery -> {
                assertThat(delivery.sent()).isTrue();
                assertThat(delivery.httpStatus()).isNull();
                assertThat(delivery.failure()).isEqualTo(CallbackFailure.CONNECT_FAILED);
            }),
            () -> assertThat(pg.state().counts().callbackSent()).isEqualTo(2),
            () -> assertThat(pg.state().callbacks()).extracting(CallbackRecord::failure)
                .containsOnly(CallbackFailure.CONNECT_FAILED)
        );
    }

    @DisplayName("redirect 응답은 결과로만 남기고 대상 주소로 따라가지 않는다.")
    @Test
    void doesNotFollowRedirect() {
        String key = createTransaction("LP-ORD-0000000204", receiver.callbackUrl());
        receiver.replyWith(new CallbackReceiverStub.Reply(307,
            Map.of("Location", receiver.baseUrl() + CallbackReceiverStub.REDIRECT_TARGET_PATH)));

        CallbackBatchResult result = awaitBatch(pg.sendCallbacks(key, 1));

        assertAll(
            () -> assertThat(result.deliveries()).extracting(CallbackDelivery::httpStatus).containsExactly(307),
            () -> assertThat(receiver.received()).hasSize(1),
            () -> assertThat(receiver.redirectTargetHits()).isZero()
        );
    }

    @DisplayName("잘못된 count·없는 거래는 IllegalArgumentException 이고 아무 작업도 접수하지 않는다.")
    @Test
    void rejectsInvalidRequest() {
        String key = createTransaction("LP-ORD-0000000205", receiver.callbackUrl());
        FixtureState before = pg.state();

        assertAll(
            () -> assertThatThrownBy(() -> pg.sendCallbacks(key, 0)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> pg.sendCallbacks(key, 11)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> pg.sendCallbacks(null, 1)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> pg.sendCallbacks("no-such-key", 1)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(pg.state()).isEqualTo(before),
            () -> assertThat(receiver.received()).isEmpty()
        );
    }

    @DisplayName("close 는 대기 작업을 미전송으로, 진행 전송은 중단으로 완료해 batch 를 남기지 않고 worker 를 종료하며 이후 접수를 거절한다.")
    @Test
    void closeCompletesQueuedAndRunningCallbacks() {
        String key = createTransaction("LP-ORD-0000000206", receiver.callbackUrl());
        receiver.hold();
        CompletableFuture<CallbackBatchResult> batch = track(pg.sendCallbacks(key, 4));
        await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> receiver.inFlight() == 2);

        long startedAt = System.nanoTime();
        pg.close();
        Duration closeElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        CallbackBatchResult result = awaitBatch(batch);

        System.out.println("[CALLBACK CLOSE] elapsed=" + closeElapsed.toMillis() + "ms deliveries=" + result.deliveries());
        assertAll(
            () -> assertThat(closeElapsed).isLessThan(Duration.ofSeconds(3)),
            () -> assertThat(result.deliveries()).hasSize(4)
                .extracting(CallbackDelivery::failure).containsOnly(CallbackFailure.ABORTED_BY_CLOSE),
            () -> assertThat(result.deliveries()).filteredOn(CallbackDelivery::sent).hasSize(2),
            () -> assertThat(pg.isTerminated()).isTrue(),
            () -> assertThat(liveThreadsNamedWith(pg.callbackThreadNamePrefix())).isZero(),
            () -> assertThatThrownBy(() -> pg.sendCallbacks(key, 1)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> pg.sendCallbacks(null, 0)).isInstanceOf(IllegalStateException.class)
        );
    }

    private String createTransaction(String orderId, String callbackUrl) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", orderId);
        fields.put("cardType", "TEST");
        fields.put("cardNo", "0000-0000-0000-0000");
        fields.put("amount", 7000);
        fields.put("callbackUrl", callbackUrl);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
            .header("X-USER-ID", "135135")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(fields)))
            .build());
        assertThat(response.statusCode()).isEqualTo(202);
        awaitHandlersFinished();
        return readJson(response.body()).get("transactionKey").textValue();
    }

    private HttpResponse<String> get(String pathAndQuery) {
        return send(HttpRequest.newBuilder(URI.create(pg.baseUrl() + pathAndQuery))
            .header("X-USER-ID", "135135")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending.add(future);
        return awaitWithin(future, BATCH_DEADLINE);
    }

    /** 응답 수신과 handler 의 journal 완료·처리 중 수 감소는 다른 시점이다. 안정 상태를 읽기 전에 완료를 기한 안에 확인한다. */
    private void awaitHandlersFinished() {
        await().atMost(OBSERVE_DEADLINE).until(() -> {
            FixtureState state = pg.state();
            return state.activeRequests() == 0
                && state.journal().stream().allMatch(entry -> entry.completedAt() != null);
        });
    }

    private CompletableFuture<CallbackBatchResult> track(CompletionStage<CallbackBatchResult> stage) {
        CompletableFuture<CallbackBatchResult> future = stage.toCompletableFuture();
        pending.add(future);
        return future;
    }

    private CallbackBatchResult awaitBatch(CompletionStage<CallbackBatchResult> stage) {
        return awaitWithin(track(stage), BATCH_DEADLINE);
    }

    private static <T> T awaitWithin(CompletableFuture<T> future, Duration deadline) {
        try {
            return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중단", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("비동기 작업 실패", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("기한 초과", e);
        }
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** 바인드 후 바로 닫아 연결을 받지 않는 포트. 짧은 사이에 다른 프로세스가 쓰면 이 테스트는 준비 실패로 드러난다. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static long liveThreadsNamedWith(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(prefix) && thread.isAlive())
            .count();
    }
}
