package com.loopers.pgsimulator;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("콜백 전송기는 저장 URL 로 알림을 1회씩 보내고 결과만 남기며 재전송하지 않는다.")
class CallbackSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final Duration OBSERVE_DEADLINE = Duration.ofSeconds(5);
    /** 콜백 client 응답 timeout(1초) 안에 hold 를 풀어야 하므로 관찰 간격을 짧게 둔다. */
    private static final Duration FAST_POLL = Duration.ofMillis(5);

    private PgSimulator pg;
    private CallbackReceiverStub receiver;
    private final List<CompletableFuture<?>> pending = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        pg = PgSimulator.start(new PgSimulatorOptions(0, attempt -> CreateOutcome.PENDING_THEN_SUCCESS, Settlement.DISABLED));
        receiver = CallbackReceiverStub.start();
    }

    /** 미완료 대기 취소 → 수신 대역 종료 → PG 종료 확인. 앞 단계가 실패해도 모두 수행한다. */
    @AfterEach
    void tearDown() throws Throwable {
        List<Throwable> failures = new ArrayList<>();
        pending.forEach(future -> future.cancel(true));
        try {
            receiver.close();
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            pg.close();
            assertThat(pg.isTerminated()).as("PG HTTP·콜백 worker 종료").isTrue();
        } catch (Throwable t) {
            failures.add(t);
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            failures.subList(1, failures.size()).forEach(first::addSuppressed);
            throw first;
        }
    }

    @DisplayName("저장 URL 로 정확한 알림 본문을 보내고 거래 상태를 바꾸지 않는다.")
    @Test
    void sendsSnapshotToStoredUrl() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000200", receiver.callbackUrl());
        pg.transactions().setStatus(transaction.transactionKey(), PaymentStatus.SUCCESS);
        TransactionSnapshot current = pg.transactions().findByKey(transaction.transactionKey()).orElseThrow();

        CallbackResult result = awaitResult(pg.callbacks().send(current));

        CallbackReceiverStub.ReceivedCallback received = receiver.received().get(0);
        JsonNode body = readJson(received.body());
        assertAll(
            () -> assertThat(result).isEqualTo(new CallbackResult(true, 200, null, null)),
            () -> assertThat(receiver.received()).hasSize(1),
            () -> assertThat(received.method()).isEqualTo("POST"),
            () -> assertThat(received.path()).isEqualTo(CallbackReceiverStub.CALLBACK_PATH),
            () -> assertThat(received.contentType()).startsWith("application/json"),
            () -> assertThat(fieldNames(body)).containsExactly("providerOrderId", "transactionKey", "status"),
            () -> assertThat(body.get("providerOrderId").textValue()).isEqualTo("LP-ORD-0000000200"),
            () -> assertThat(body.get("transactionKey").textValue()).isEqualTo(transaction.transactionKey()),
            () -> assertThat(body.get("status").textValue()).isEqualTo("SUCCESS"),
            () -> assertThat(pg.transactions().findAll()).containsExactly(current)
        );
    }

    @DisplayName("상태 변경만으로는 콜백을 보내지 않는다.")
    @Test
    void doesNotSendOnStatusChange() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000207", receiver.callbackUrl());

        pg.transactions().setStatus(transaction.transactionKey(), PaymentStatus.SUCCESS);

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> receiver.received().isEmpty());
    }

    @DisplayName("전달받은 snapshot 을 보내므로 전송 대기 중 상태가 바뀌어도 보낸 시점의 상태를 알린다. 전송 worker 는 2개다.")
    @Test
    void sendsGivenSnapshotWithTwoWorkers() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000201", receiver.callbackUrl());
        pg.transactions().setStatus(transaction.transactionKey(), PaymentStatus.SUCCESS);
        TransactionSnapshot success = pg.transactions().findByKey(transaction.transactionKey()).orElseThrow();
        receiver.hold();

        List<CompletableFuture<CallbackResult>> sends = IntStream.range(0, 3)
            .mapToObj(i -> track(pg.callbacks().send(success)))
            .toList();
        await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> receiver.inFlight() == 2);
        long liveCallbackThreads = liveThreadsNamedWith(pg.callbackThreadNamePrefix());
        pg.transactions().setStatus(transaction.transactionKey(), PaymentStatus.FAILED);
        boolean doneWhileHolding = sends.stream().anyMatch(CompletableFuture::isDone);
        receiver.release();

        List<CallbackResult> results = sends.stream().map(CallbackSenderTest::awaitResult).toList();

        assertAll(
            () -> assertThat(liveCallbackThreads).as("콜백 전용 worker 2개").isEqualTo(CallbackSender.WORKERS),
            () -> assertThat(doneWhileHolding).isFalse(),
            () -> assertThat(results).extracting(CallbackResult::httpStatus).containsOnly(200),
            () -> assertThat(receiver.received()).hasSize(3)
                .allSatisfy(received -> assertThat(readJson(received.body()).get("status").textValue())
                    .as("전달한 snapshot").isEqualTo("SUCCESS")),
            () -> assertThat(pg.transactions().findAll().get(0).status()).as("전송은 상태를 바꾸지 않음")
                .isEqualTo(PaymentStatus.FAILED)
        );
    }

    @DisplayName("202·409·500 응답을 각 전송 결과로 보존하고 응답 코드를 이유로 다시 보내지 않는다.")
    @Test
    void preservesResponsesWithoutResend() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000202", receiver.callbackUrl());
        receiver.replyWith(new CallbackReceiverStub.Reply(202, Map.of()), new CallbackReceiverStub.Reply(409, Map.of()),
            new CallbackReceiverStub.Reply(500, Map.of()));

        List<CallbackResult> results = IntStream.range(0, 3)
            .mapToObj(i -> track(pg.callbacks().send(transaction)))
            .toList()
            .stream()
            .map(CallbackSenderTest::awaitResult)
            .toList();

        assertAll(
            () -> assertThat(results).extracting(CallbackResult::httpStatus).containsExactlyInAnyOrder(202, 409, 500),
            () -> assertThat(results).extracting(CallbackResult::failure).containsOnlyNulls(),
            () -> assertThat(receiver.received()).hasSize(3)
        );
    }

    @DisplayName("연결할 수 없는 주소면 통신 오류를 결과로 남긴다.")
    @Test
    void recordsConnectionFailure() throws IOException {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000203",
            "http://127.0.0.1:" + closedPort() + "/api/v1/payments/callback");

        CallbackResult result = awaitResult(pg.callbacks().send(transaction));

        assertAll(
            () -> assertThat(result.sent()).isTrue(),
            () -> assertThat(result.httpStatus()).isNull(),
            () -> assertThat(result.failure()).isEqualTo(CallbackFailure.CONNECT_FAILED),
            () -> assertThat(result.errorDetail()).isNotBlank()
        );
    }

    @DisplayName("응답이 1초 안에 오지 않으면 timeout 을 결과로 남긴다.")
    @Test
    void recordsResponseTimeout() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000208", receiver.callbackUrl());
        receiver.hold();

        CallbackResult result = awaitResult(pg.callbacks().send(transaction));
        receiver.release();

        assertAll(
            () -> assertThat(result.sent()).isTrue(),
            () -> assertThat(result.failure()).isEqualTo(CallbackFailure.TIMEOUT),
            () -> assertThat(receiver.received()).hasSize(1)
        );
    }

    @DisplayName("redirect 응답은 결과로만 남기고 대상 주소로 따라가지 않는다.")
    @Test
    void doesNotFollowRedirect() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000204", receiver.callbackUrl());
        receiver.replyWith(new CallbackReceiverStub.Reply(307,
            Map.of("Location", receiver.baseUrl() + CallbackReceiverStub.REDIRECT_TARGET_PATH)));

        CallbackResult result = awaitResult(pg.callbacks().send(transaction));

        assertAll(
            () -> assertThat(result.httpStatus()).isEqualTo(307),
            () -> assertThat(receiver.received()).hasSize(1),
            () -> assertThat(receiver.redirectTargetHits()).isZero()
        );
    }

    @DisplayName("close 는 대기 작업을 미전송으로, 진행 전송은 중단으로 완료해 미완료를 남기지 않고 worker 를 종료하며, 이후 전송은 미전송으로 완료한다.")
    @Test
    void closeCompletesQueuedAndRunningCallbacks() {
        TransactionSnapshot transaction = createTransaction("LP-ORD-0000000206", receiver.callbackUrl());
        receiver.hold();
        List<CompletableFuture<CallbackResult>> sends = IntStream.range(0, 4)
            .mapToObj(i -> track(pg.callbacks().send(transaction)))
            .toList();
        await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> receiver.inFlight() == 2);

        long startedAt = System.nanoTime();
        pg.close();
        Duration closeElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        List<CallbackResult> results = sends.stream().map(CallbackSenderTest::awaitResult).toList();
        CallbackResult afterClose = awaitResult(pg.callbacks().send(transaction));

        assertAll(
            () -> assertThat(closeElapsed).isLessThan(Duration.ofSeconds(3)),
            () -> assertThat(results).hasSize(4)
                .extracting(CallbackResult::failure).containsOnly(CallbackFailure.ABORTED_BY_CLOSE),
            () -> assertThat(results).filteredOn(CallbackResult::sent).hasSize(2),
            () -> assertThat(pg.isTerminated()).isTrue(),
            () -> assertThat(liveThreadsNamedWith(pg.callbackThreadNamePrefix())).isZero(),
            () -> assertThat(afterClose).isEqualTo(new CallbackResult(false, null, CallbackFailure.ABORTED_BY_CLOSE, null))
        );
    }

    private TransactionSnapshot createTransaction(String orderId, String callbackUrl) {
        return pg.transactions().create(orderId, 7000, callbackUrl, PaymentStatus.PENDING);
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        return future;
    }

    private static <T> T awaitResult(CompletableFuture<T> future) {
        try {
            return future.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중단", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("비동기 작업 실패", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("기한 초과", e);
        }
    }

    /** 바인드 후 바로 닫아 연결을 받지 않는 포트. 짧은 사이에 다른 프로세스가 쓰면 이 테스트는 준비 실패로 드러난다. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            return socket.getLocalPort();
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
