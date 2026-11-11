package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.HoldingAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * HTTP 테스트 정리 판단의 실패 분기. 실제 DB·Redis 없이 메모리 점유 대역 위에서 수용 decorator 관찰만 재현한다.
 * 실제 서버 요청은 만들지 않으므로 통제되지 않은 DB 작업이 남지 않는다.
 */
@DisplayName("정리 판단은 응답 수신과 서버 측 반환 관찰이 모두 있을 때만 허용하고, 미확인은 뒤의 성공으로 지워지지 않는다.")
class ServerSettlementTest {

    private static final Duration SHORT = Duration.ofMillis(200);

    /** 메모리 Set 대역. failNext 가 켜지면 다음 획득을 예외로 끝낸다. */
    private static final class MemoryPermits implements CouponAdmissionRepository {
        private final Set<String> permits = ConcurrentHashMap.newKeySet();
        private volatile boolean failNext;
        private volatile CountDownLatch blockAcquire;

        @Override
        public Acquisition tryAcquire(String token, int capacity) {
            if (blockAcquire != null) {
                try {
                    blockAcquire.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("획득 결과 불명(대역)");
            }
            if (permits.contains(token)) {
                return Acquisition.ACQUIRED;
            }
            if (permits.size() >= capacity) {
                return Acquisition.FULL;
            }
            permits.add(token);
            return Acquisition.ACQUIRED;
        }

        @Override
        public long release(String token) {
            return permits.remove(token) ? 1 : 0;
        }
    }

    private final MemoryPermits permits = new MemoryPermits();
    private final HoldingAdmissionRepository holding = new HoldingAdmissionRepository(permits);
    private final ServerSettlement settlement = new ServerSettlement(holding);

    @DisplayName("ACQUIRED 뒤 반환이 관찰되고 응답을 받았으면 정리할 수 있다. FULL 만 받은 실행도 쓰기 TX 로 가지 않아 근거가 된다.")
    @Test
    void settledWhenReleasedOrFull() throws Exception {
        holding.tryAcquire("a", 1);
        holding.tryAcquire("b", 1);
        holding.release("a");
        settlement.observe("201", () -> ResponseEntity.status(201).build());
        settlement.observe("429", () -> ResponseEntity.status(429).build());

        assertThat(settlement.awaitSettled(SHORT)).isEmpty();
    }

    @DisplayName("ACQUIRED 뒤 반환이 관찰되지 않으면 유한 대기 뒤에도 미확인 token 으로 남는다.")
    @Test
    void acquiredWithoutRelease() throws Exception {
        holding.tryAcquire("held", 3);

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("held");
    }

    @DisplayName("대기 중에 반환이 관찰되면 정리를 허용한다.")
    @Test
    void lateReleaseWithinDeadline() throws Exception {
        holding.tryAcquire("late", 3);
        Thread releaser = new Thread(() -> {
            sleep(50);
            holding.release("late");
        });
        releaser.start();

        List<String> reasons = settlement.awaitSettled(Duration.ofSeconds(2));
        releaser.join(2_000);

        assertAll(
            () -> assertThat(reasons).isEmpty(),
            () -> assertThat(releaser.isAlive()).isFalse()
        );
    }

    @DisplayName("획득 예외(늦게 추가될 수 있음)는 뒤의 다른 실행이 정상 반환해도 미확인으로 남는다.")
    @Test
    void failedAcquireIsSticky() throws Exception {
        permits.failNext = true;
        assertThatThrownBy(() -> holding.tryAcquire("unknown", 3)).isInstanceOf(IllegalStateException.class);
        holding.tryAcquire("ok", 3);
        holding.release("ok");
        settlement.observe("503", () -> ResponseEntity.status(503).build());
        settlement.observe("201", () -> ResponseEntity.status(201).build());

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("unknown");
    }

    @DisplayName("응답을 받지 못한 요청은 원래 예외를 그대로 던지고, 이후 token 이 모두 반환돼도 미확인으로 남는다.")
    @Test
    void clientFailureIsNotServerCompletion() throws Exception {
        ResourceAccessException failure = new ResourceAccessException("connection reset(대역)");
        assertThatThrownBy(() -> settlement.observe("POST issue", () -> {
            throw failure;
        })).isSameAs(failure);
        holding.tryAcquire("t", 3);
        holding.release("t");

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("POST issue", "응답 없음");
    }

    @DisplayName("500 응답은 종료 불명일 수 있어 근거로 쓰지 않는다.")
    @Test
    void internalErrorIsNotSettled() throws Exception {
        settlement.observe("POST issue", () -> ResponseEntity.status(500).build());

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("500");
    }

    @DisplayName("획득 명령이 진행 중이면 미확인이고, 끝나고 반환되면 정리를 허용한다.")
    @Test
    void inFlightAcquire() throws Exception {
        permits.blockAcquire = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> acquiring = executor.submit(() -> holding.tryAcquire("slow", 3));
            waitUntil(() -> holding.sentTokens().contains("slow"));

            List<String> whileRunning = settlement.awaitSettled(SHORT);

            permits.blockAcquire.countDown();
            acquiring.get(5, TimeUnit.SECONDS);
            holding.release("slow");
            assertAll(
                () -> assertThat(whileRunning).singleElement().asString().contains("slow"),
                () -> assertThat(settlement.awaitSettled(SHORT)).isEmpty()
            );
        } finally {
            permits.blockAcquire.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("테스트가 직접 넣은 token 은 서버 실행이 아니므로 판단에서 뺀다.")
    @Test
    void testTokensExcluded() throws Exception {
        settlement.markTestToken("own");
        holding.tryAcquire("own", 3);

        assertThat(settlement.awaitSettled(SHORT)).isEmpty();
    }

    @DisplayName("응답을 잃은 요청이 아직 token 을 만들지 않았어도 첫 정리를 보류하고, 그 뒤 키가 비어 있고 다른 요청이 성공해도 다음 사례의 시작·정리·기록 초기화를 계속 막는다.")
    @Test
    void withheldStatePersistsAcrossCases() {
        // 첫 사례: 응답 없음. 서버 실행은 사전 DB 조회 중이라 Redis token 이 아직 없다고 가정한다.
        assertThatThrownBy(() -> settlement.observe("POST issue", () -> {
            throw new ResourceAccessException("connection reset(대역)");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(holding.sentTokens()).isEmpty();
        assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, SHORT))
            .hasMessageContaining("정리를 보류").hasMessageContaining("응답 없음");

        // 같은 컨텍스트에서 다른 요청이 정상 끝나도 풀리지 않는다.
        holding.tryAcquire("later", 3);
        holding.release("later");
        settlement.observe("201", () -> ResponseEntity.status(201).build());

        // 다음 사례: 시작(키 검사·준비·수용 열기 전)과 기록 초기화가 막힌다.
        assertAll(
            () -> assertThatThrownBy(settlement::requireNotWithheld).hasMessageContaining("응답 없음"),
            () -> assertThatThrownBy(settlement::reset).hasMessageContaining("응답 없음"),
            // 다음 사례의 정리도 막힌다(@BeforeEach 실패 뒤에도 @AfterEach 는 실행된다).
            () -> assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, SHORT))
                .hasMessageContaining("응답 없음")
        );
    }

    @DisplayName("클라이언트 worker 종료를 확인하지 못하면 서버 측 관찰이 깨끗해도 보류하고 유지한다.")
    @Test
    void workersNotTerminatedWithholds() {
        assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(false, SHORT)).hasMessageContaining("worker");
        assertThatThrownBy(settlement::requireNotWithheld).hasMessageContaining("worker");
    }

    @DisplayName("종료 확인 대기 중 interrupt 는 근거 확보 실패로 보고 보류하며 interrupt 상태를 복원한다.")
    @Test
    void interruptedWhileWaitingWithholds() {
        holding.tryAcquire("pending", 3);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, Duration.ofSeconds(2)))
                .hasMessageContaining("interrupt");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThatThrownBy(settlement::requireNotWithheld).hasMessageContaining("interrupt");
    }

    @DisplayName("근거가 모두 있으면 정리를 허용하고 보류 상태가 생기지 않으며 다음 사례가 시작할 수 있다. 새 컨텍스트의 객체는 이전 보류와 무관하다.")
    @Test
    void confirmedCleanupDoesNotWithhold() {
        holding.tryAcquire("a", 3);
        holding.release("a");
        settlement.observe("201", () -> ResponseEntity.status(201).build());

        settlement.confirmCleanupOrWithhold(true, SHORT);

        settlement.requireNotWithheld();
        settlement.reset();
        ServerSettlement freshContext = new ServerSettlement(new HoldingAdmissionRepository(new MemoryPermits()));
        freshContext.requireNotWithheld();
    }

    @DisplayName("raw 요청은 전송 전 등록되면 아직 token 이 없어도 정리를 막고, 다른 요청이 끝나도 풀리지 않는다.")
    @Test
    void rawRequestBlocksUntilItsOwnReleaseIsObserved() {
        String key = settlement.registerRaw("raw POST issue");
        holding.tryAcquire("other", 3);
        holding.release("other");

        assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, SHORT))
            .hasMessageContaining("raw POST issue");
        assertThatThrownBy(settlement::requireNotWithheld).hasMessageContaining("raw POST issue");
    }

    @DisplayName("raw 요청은 그 요청 token 의 반환이 관찰돼야만 해소되고, 근거 없는 해소 시도는 거절된다.")
    @Test
    void rawRequestSettlesOnlyWithReleaseEvidence() {
        String key = settlement.registerRaw("raw POST issue");
        holding.tryAcquire("raw-token", 3);

        assertThatThrownBy(() -> settlement.settleRaw(key, "raw-token")).hasMessageContaining("근거");
        assertThatThrownBy(() -> settlement.settleRaw(key, null)).hasMessageContaining("근거");
        holding.release("raw-token");
        settlement.settleRaw(key, "raw-token");

        settlement.confirmCleanupOrWithhold(true, SHORT);
        settlement.requireNotWithheld();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new IllegalStateException("조건 대기 초과");
            }
            sleep(5);
        }
    }
}
