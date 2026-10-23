package com.loopers.infrastructure.payment;

import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.GateStatus;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * PG 전용 RestClient·Apache client 설정이 실제 HTTP 에서 적용되는지 테스트 PG fixture 로 확인한다.
 * Gateway·응답 해석·회로는 아직 없으므로 RestClient 를 직접 호출한다. 결과는 client 설정의 근거이며
 * UNKNOWN·재고 유지 같은 업무 판정의 검증이 아니다.
 */
@DisplayName("PG 전용 HTTP client 는 풀·응답 timeout·retry/redirect 비활성 설정대로 동작한다.")
class PgHttpClientBehaviorTest extends PgAdapterTestSupport {

    @DisplayName("설정한 base-url 과 고정 계정 헤더로 요청한다.")
    @Test
    void usesBaseUrlAndAccountHeader() {
        withPgClient(client -> {
            ResponseEntity<String> response = lookupByOrder(client, "LP-ORD-0000000300");

            awaitHandlersFinished();
            assertAll(
                () -> assertThat(response.getStatusCode().value()).as("헤더 검사 통과").isEqualTo(200),
                () -> assertThat(response.getBody()).isEqualTo("[]"),
                () -> assertThat(pg.state().journal()).extracting(JournalEntry::target)
                    .containsExactly("LP-ORD-0000000300")
            );
        });
    }

    @DisplayName("응답이 1초 안에 오지 않으면 읽기 timeout 으로 끝나고 다시 요청하지 않는다.")
    @Test
    void timesOutReadWithoutRetry() {
        pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000301", ResponsePlan.delay(3_000));

        withPgClient(client -> {
            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> lookupByOrder(client, "LP-ORD-0000000301"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            awaitHandlersFinished();

            System.out.println("[PG READ TIMEOUT] elapsed=" + elapsed.toMillis() + "ms " + describe(failure));
            assertAll(
                () -> assertThat(failure).isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class),
                () -> assertThat(elapsed).isBetween(Duration.ofMillis(950), Duration.ofMillis(2_500)),
                () -> assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1)
            );
        });
    }

    @DisplayName("응답 수신 도중 연결이 끊겨도 생성 POST·조회 GET 을 다시 보내지 않는다.")
    @Test
    void doesNotRetryAfterBrokenResponse() {
        pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000302", ResponsePlan.closeEarly());
        pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000302", ResponsePlan.closeEarly());

        withPgClient(client -> {
            Throwable postFailure = catchThrowable(() -> client.post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "orderId", "LP-ORD-0000000302",
                    "cardType", "TEST",
                    "cardNo", "0000-0000-0000-0000",
                    "amount", 7000,
                    "callbackUrl", "http://127.0.0.1:8080/api/v1/payments/callback"))
                .retrieve()
                .toEntity(String.class));
            Throwable getFailure = catchThrowable(() -> lookupByOrder(client, "LP-ORD-0000000302"));
            awaitHandlersFinished();

            FixtureState state = pg.state();
            System.out.println("[PG CLOSE_EARLY] post=" + describe(postFailure) + " get=" + describe(getFailure));
            assertAll(
                // 헤더 수신 뒤 본문 읽기 중 끊김은 ResourceAccessException 이 아니라 본문 추출 단계의 RestClientException 이다.
                () -> assertThat(postFailure).isInstanceOf(RestClientException.class)
                    .hasRootCauseInstanceOf(ConnectionClosedException.class),
                () -> assertThat(getFailure).isInstanceOf(RestClientException.class)
                    .hasRootCauseInstanceOf(ConnectionClosedException.class),
                () -> assertThat(state.counts().createReceived()).isEqualTo(1),
                () -> assertThat(state.counts().lookupByOrderReceived()).isEqualTo(1),
                () -> assertThat(state.transactions()).hasSize(1)
            );
        });
    }

    @DisplayName("3xx 응답을 그대로 돌려주고 Location 을 따라가지 않는다.")
    @Test
    void doesNotFollowRedirect() {
        String location = pg.baseUrl() + "/api/v1/payments?orderId=LP-ORD-0000000304";
        pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000303",
            ResponsePlan.raw(302, "", Map.of("Location", location)));

        withPgClient(client -> {
            ResponseEntity<String> response = lookupByOrder(client, "LP-ORD-0000000303");
            awaitHandlersFinished();

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(302),
                () -> assertThat(response.getHeaders().getFirst("Location")).isEqualTo(location),
                () -> assertThat(pg.state().journal()).extracting(JournalEntry::target)
                    .containsExactly("LP-ORD-0000000303")
            );
        });
    }

    @DisplayName("연결 10개가 모두 쓰이는 동안 다음 요청은 풀 대기 200ms 뒤 PG 로 보내지 않고 실패한다.")
    @Test
    void failsPoolAcquireWhenAllConnectionsBusy() {
        int maxConnections = 10;
        IntStream.range(0, maxConnections).forEach(i ->
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, heldOrderId(i), ResponsePlan.gate("g-" + i)));

        withPgClient(client -> {
            List<Future<?>> held = new ArrayList<>();
            IntStream.range(0, maxConnections).forEach(i ->
                held.add(callers.submit(() -> catchThrowable(() -> lookupByOrder(client, heldOrderId(i))))));
            await().atMost(OBSERVE_DEADLINE).pollInterval(FAST_POLL).until(() -> {
                FixtureState state = pg.state();
                return state.activeRequests() == maxConnections
                    && state.gates().stream().allMatch(gate -> gate.status() == GateStatus.WAITING);
            });

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> lookupByOrder(client, "LP-ORD-0000000399"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            long receivedWhileFull = pg.state().counts().lookupByOrderReceived();
            IntStream.range(0, maxConnections).forEach(i -> pg.releaseGate("g-" + i));
            for (Future<?> future : held) {
                future.get(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS);
            }

            System.out.println("[PG POOL ACQUIRE] elapsed=" + elapsed.toMillis() + "ms " + describe(failure));
            assertAll(
                () -> assertThat(failure).isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(ConnectionRequestTimeoutException.class),
                () -> assertThat(elapsed).isBetween(Duration.ofMillis(190), Duration.ofMillis(900)),
                () -> assertThat(receivedWhileFull).as("11번째 요청은 PG 수신 0").isEqualTo(maxConnections)
            );
        });
    }

    private void withPgClient(ThrowingConsumer<RestClient> test) {
        PgHttpConfigTest.RUNNER
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> test.accept(context.getBean("pgRestClient", RestClient.class)));
    }

    private static ResponseEntity<String> lookupByOrder(RestClient client, String orderId) {
        return client.get().uri("/api/v1/payments?orderId={orderId}", orderId).retrieve().toEntity(String.class);
    }

    private static String heldOrderId(int index) {
        return "LP-ORD-00000003" + (10 + index);
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return failure.getClass().getSimpleName() + " <- " + root.getClass().getName() + ": " + root.getMessage();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
