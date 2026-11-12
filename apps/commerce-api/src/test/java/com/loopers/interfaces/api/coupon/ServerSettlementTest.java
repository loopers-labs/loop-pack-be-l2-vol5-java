package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.HoldingWaitingRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * HTTP 테스트 정리 판단의 실패 분기. 실제 DB·Redis 없이 메모리 대역 위에서 대기실 decorator 의 start·finish 관찰만 재현한다.
 * 실제 서버 요청은 만들지 않으므로 통제되지 않은 DB 작업이 남지 않는다.
 */
@DisplayName("정리 판단은 응답 수신과 서버 측 finish 관찰이 모두 있을 때만 허용하고, 미확인은 뒤의 성공으로 지워지지 않는다.")
class ServerSettlementTest {

    private static final Duration SHORT = Duration.ofMillis(200);

    /** start 는 "not-ready" 로 시작하는 ID 만 NOT_READY, 나머지는 STARTED. failNext 면 다음 start 를 예외로 끝낸다. */
    private static final class MemoryWaiting implements CouponAdmissionRepository {
        private volatile boolean failNext;
        private volatile CountDownLatch blockStart;

        @Override
        public Start start(Long campaignId, Long userId, String waitingId) {
            if (blockStart != null) {
                try {
                    blockStart.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("시작 결과 불명(대역)");
            }
            return new Start(waitingId.startsWith("not-ready") ? StartOutcome.NOT_READY : StartOutcome.STARTED, null);
        }

        @Override
        public FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
            return FinishOutcome.APPLIED;
        }

        @Override
        public Registration register(Long campaignId, Long userId, String newWaitingId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit) {
            throw new UnsupportedOperationException();
        }
    }

    private final MemoryWaiting memory = new MemoryWaiting();
    private final HoldingWaitingRepository holding = HoldingWaitingRepository.real(memory);
    private final ServerSettlement settlement = new ServerSettlement(holding);

    private void start(String waitingId) {
        holding.start(1L, 1L, waitingId);
    }

    private void finish(String waitingId) {
        holding.finish(1L, 1L, waitingId, CouponWaitingState.ISSUED);
    }

    @DisplayName("STARTED 뒤 finish 가 관찰되고 응답을 받았으면 정리할 수 있다. STARTED 가 아닌 start 결과도 쓰기 TX 로 가지 않아 근거가 된다.")
    @Test
    void settledWhenFinishedOrNotStarted() throws Exception {
        start("a");
        start("not-ready-b");
        finish("a");
        settlement.observe("201", () -> ResponseEntity.status(201).build());
        settlement.observe("409", () -> ResponseEntity.status(409).build());

        assertThat(settlement.awaitSettled(SHORT)).isEmpty();
    }

    @DisplayName("STARTED 뒤 finish 가 관찰되지 않으면 유한 대기 뒤에도 미확인으로 남는다.")
    @Test
    void startedWithoutFinish() throws Exception {
        start("held");

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("held");
    }

    @DisplayName("대기 중에 finish 가 관찰되면 정리를 허용한다.")
    @Test
    void lateFinishWithinDeadline() throws Exception {
        start("late");
        Thread finisher = new Thread(() -> {
            sleep(50);
            finish("late");
        });
        finisher.start();

        List<String> reasons = settlement.awaitSettled(Duration.ofSeconds(2));
        finisher.join(2_000);

        assertAll(
            () -> assertThat(reasons).isEmpty(),
            () -> assertThat(finisher.isAlive()).isFalse()
        );
    }

    @DisplayName("start 예외(늦게 적용됐을 수 있음)는 뒤의 다른 실행이 정상 종료해도 미확인으로 남는다.")
    @Test
    void failedStartIsSticky() throws Exception {
        memory.failNext = true;
        assertThatThrownBy(() -> start("unknown")).isInstanceOf(IllegalStateException.class);
        start("ok");
        finish("ok");
        settlement.observe("503", () -> ResponseEntity.status(503).build());
        settlement.observe("201", () -> ResponseEntity.status(201).build());

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("unknown");
    }

    @DisplayName("응답을 받지 못한 요청은 원래 예외를 그대로 던지고, 이후 실행이 모두 끝나도 미확인으로 남는다.")
    @Test
    void clientFailureIsNotServerCompletion() throws Exception {
        ResourceAccessException failure = new ResourceAccessException("connection reset(대역)");
        assertThatThrownBy(() -> settlement.observe("POST issue", () -> {
            throw failure;
        })).isSameAs(failure);
        start("t");
        finish("t");

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("POST issue", "응답 없음");
    }

    @DisplayName("500 응답은 종료 불명일 수 있어 근거로 쓰지 않는다.")
    @Test
    void internalErrorIsNotSettled() throws Exception {
        settlement.observe("POST issue", () -> ResponseEntity.status(500).build());

        assertThat(settlement.awaitSettled(SHORT)).singleElement().asString().contains("500");
    }

    @DisplayName("start 명령이 진행 중이면 미확인이고, 끝나고 finish 되면 정리를 허용한다.")
    @Test
    void inFlightStart() throws Exception {
        memory.blockStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> starting = executor.submit(() -> start("slow"));
            waitUntil(() -> holding.startedWaitingIds().contains("slow"));

            List<String> whileRunning = settlement.awaitSettled(SHORT);

            memory.blockStart.countDown();
            starting.get(5, TimeUnit.SECONDS);
            finish("slow");
            assertAll(
                () -> assertThat(whileRunning).singleElement().asString().contains("slow"),
                () -> assertThat(settlement.awaitSettled(SHORT)).isEmpty()
            );
        } finally {
            memory.blockStart.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("응답을 잃은 요청이 아직 start 전이어도 첫 정리를 보류하고, 그 뒤 다른 요청이 성공해도 다음 사례의 시작·정리·기록 초기화를 계속 막는다.")
    @Test
    void withheldStatePersistsAcrossCases() {
        // 첫 사례: 응답 없음. 서버 실행은 사전 DB 조회 중이라 아직 start 전이라고 가정한다.
        assertThatThrownBy(() -> settlement.observe("POST issue", () -> {
            throw new ResourceAccessException("connection reset(대역)");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(holding.startedWaitingIds()).isEmpty();
        assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, SHORT))
            .hasMessageContaining("정리를 보류").hasMessageContaining("응답 없음");

        // 같은 컨텍스트에서 다른 요청이 정상 끝나도 풀리지 않는다.
        start("later");
        finish("later");
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
        start("pending");
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
        start("a");
        finish("a");
        settlement.observe("201", () -> ResponseEntity.status(201).build());

        settlement.confirmCleanupOrWithhold(true, SHORT);

        settlement.requireNotWithheld();
        settlement.reset();
        ServerSettlement freshContext = new ServerSettlement(HoldingWaitingRepository.real(new MemoryWaiting()));
        freshContext.requireNotWithheld();
    }

    @DisplayName("raw 요청은 전송 전 등록되면 아직 start 전이어도 정리를 막고, 다른 요청이 끝나도 풀리지 않는다.")
    @Test
    void rawRequestBlocksUntilItsOwnFinishIsObserved() {
        settlement.registerRaw("raw POST issue");
        start("other");
        finish("other");

        assertThatThrownBy(() -> settlement.confirmCleanupOrWithhold(true, SHORT))
            .hasMessageContaining("raw POST issue");
        assertThatThrownBy(settlement::requireNotWithheld).hasMessageContaining("raw POST issue");
    }

    @DisplayName("raw 요청은 그 요청 waitingId 의 finish 가 관찰돼야만 해소되고, 근거 없는 해소 시도는 거절된다.")
    @Test
    void rawRequestSettlesOnlyWithFinishEvidence() {
        String key = settlement.registerRaw("raw POST issue");
        start("raw-id");

        assertThatThrownBy(() -> settlement.settleRaw(key, "raw-id")).hasMessageContaining("근거");
        assertThatThrownBy(() -> settlement.settleRaw(key, null)).hasMessageContaining("근거");
        finish("raw-id");
        settlement.settleRaw(key, "raw-id");

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
