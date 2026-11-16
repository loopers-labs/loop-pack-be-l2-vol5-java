package com.loopers.verification.localredis;

import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.verification.localredis.CouponCacheLocalRedisFaultIntegrationTest.RecordingCacheRepository;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.FaultLog;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.PauseGuard;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.RecordingAdmissionRepository;
import com.loopers.infrastructure.coupon.WorkerTermination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 로컬 Redis 장애 테스트의 실패 처리 장치를 Docker·Redis 없이 대역으로 확인한다. 실제 컨테이너를 멈추지 않는다.
 * 장애 테스트 본체와 달리 환경변수 없이 일반 실행에서 돈다.
 */
@DisplayName("장애 테스트의 정리 보류는 컨텍스트 동안 유지되고, pause 결과가 불명해도 확인된 컨테이너의 실제 상태로 복구한다.")
class CouponLocalRedisFaultGuardTest {

    private static final String ID = "verified-id";
    private static final List<String> PAUSE_INSPECT_UNPAUSE =
        List.of("pause " + ID, "inspect -f {{.State.Paused}} " + ID, "unpause " + ID);

    @TempDir
    Path dir;

    private final WorkerTermination workers = new WorkerTermination();

    /** 대역 worker 가 끝났음을 확인한다. 끝나지 않았으면 임시 자원 정리를 보류하고 실패한다. */
    @AfterEach
    void requireWorkersEnded() {
        if (!workers.safeToClean()) {
            throw new IllegalStateException("Guard 검사 worker 종료를 확인하지 못했다.");
        }
    }

    /** start 는 STARTED, finish 는 APPLIED 로만 답하는 메모리 대역. 호출된 waitingId 를 남긴다. */
    private static final class MemoryWaiting implements CouponAdmissionRepository {
        private final Set<String> processing = ConcurrentHashMap.newKeySet();

        @Override
        public Start start(Long campaignId, Long userId, String waitingId) {
            processing.add(waitingId);
            return new Start(StartOutcome.STARTED, null);
        }

        @Override
        public FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
            processing.remove(waitingId);
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

    /** 명령을 기록하고 지정한 응답·실패를 돌려주는 docker CLI 대역. */
    private static final class FakeCli implements PauseGuard.Cli {
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        volatile boolean pauseFails;
        volatile String pausedState = "true";
        volatile boolean inspectFails;
        volatile boolean unpauseFails;
        /** 지정하면 pause 가 이 latch 가 풀릴 때까지 멈춘다(느린 CLI). */
        volatile CountDownLatch releasePause;
        final CountDownLatch pauseEntered = new CountDownLatch(1);
        /** 복구 명령(inspect·unpause) 시점의 호출 스레드 interrupt 상태. */
        final List<Boolean> interruptedAtRestore = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String run(String... args) throws Exception {
            commands.add(String.join(" ", args));
            if (!args[0].equals("pause")) {
                interruptedAtRestore.add(Thread.currentThread().isInterrupted());
            }
            switch (args[0]) {
                case "pause" -> {
                    pauseEntered.countDown();
                    if (releasePause != null && !releasePause.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("대역 pause 해제 대기 초과");
                    }
                    if (pauseFails) {
                        throw new IllegalStateException("docker 명령 대기 초과(대역)");
                    }
                }
                case "inspect" -> {
                    if (inspectFails) {
                        throw new IllegalStateException("inspect 실패(대역)");
                    }
                    return pausedState;
                }
                case "unpause" -> {
                    if (unpauseFails) {
                        throw new IllegalStateException("unpause 실패(대역)");
                    }
                }
                default -> throw new IllegalStateException("예상하지 않은 명령: " + commands);
            }
            return "";
        }
    }

    private FaultLog log() {
        return FaultLog.at(dir.resolve("fault.jsonl"));
    }

    private String logText() throws Exception {
        Path path = dir.resolve("fault.jsonl");
        return Files.exists(path) ? Files.readString(path) : "";
    }

    @DisplayName("실패한 요청이 start 전이었어도 보류하면 다음 사례가 막히고, 다른 요청의 성공·빈 Set 으로 풀리지 않는다.")
    @Test
    void withheldContextBlocksNextCase() throws Exception {
        MemoryWaiting waiting = new MemoryWaiting();
        RecordingAdmissionRepository recording = new RecordingAdmissionRepository(waiting, log());

        recording.withhold("Q9 응답 없음, start 전(대역)");
        recording.next("Q10", 2L, 1L);
        recording.start(1L, 2L, "other");
        recording.finish(1L, 2L, "other", CouponWaitingState.ISSUED);

        assertAll(
            () -> assertThat(waiting.processing).isEmpty(),
            () -> assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("Q9 응답 없음"),
            () -> assertThat(logText()).contains("context-withheld")
        );
    }

    @DisplayName("보류가 없으면 다음 사례가 시작할 수 있다.")
    @Test
    void notWithheldAllowsNextCase() {
        RecordingAdmissionRepository recording = new RecordingAdmissionRepository(new MemoryWaiting(), log());

        recording.requireNotWithheld();
    }

    @DisplayName("pause CLI 가 실패·불명이어도 시도 사실을 남기고, 복구 때 확인된 컨테이너의 실제 상태가 paused 면 unpause 한다.")
    @Test
    void unknownPauseResultIsRestoredFromActualState() throws Exception {
        FakeCli cli = new FakeCli();
        cli.pauseFails = true;
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target("verified-id");

        assertThatThrownBy(() -> guard.pause("Q9")).hasMessageContaining("대기 초과");
        guard.restore("Q9");

        assertAll(
            () -> assertThat(cli.commands).containsExactly("pause verified-id", "inspect -f {{.State.Paused}} verified-id", "unpause verified-id"),
            () -> assertThat(logText()).contains("pause-attempt", "\"event\":\"unpause\"")
        );
    }

    @DisplayName("실제 상태가 paused 가 아니면 unpause 하지 않고 그 사실을 기록한다.")
    @Test
    void notPausedIsNotUnpaused() throws Exception {
        FakeCli cli = new FakeCli();
        cli.pausedState = "false";
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target("verified-id");

        guard.pause("Q10");
        guard.restore("Q10");

        assertAll(
            () -> assertThat(cli.commands).containsExactly("pause verified-id", "inspect -f {{.State.Paused}} verified-id"),
            () -> assertThat(logText()).contains("restore-not-paused")
        );
    }

    @DisplayName("상태 조회·unpause 실패는 숨기지 않고 기록한 뒤 던진다.")
    @Test
    void restoreFailuresAreRecordedAndThrown() throws Exception {
        FakeCli inspectFails = new FakeCli();
        inspectFails.inspectFails = true;
        PauseGuard first = new PauseGuard(inspectFails, log());
        first.target("verified-id");
        first.pause("Q9");

        FakeCli unpauseFails = new FakeCli();
        unpauseFails.unpauseFails = true;
        PauseGuard second = new PauseGuard(unpauseFails, log());
        second.target("verified-id");
        second.pause("Q10");

        assertAll(
            () -> assertThatThrownBy(() -> first.restore("Q9")).hasMessageContaining("상태를 확인하지 못했다"),
            () -> assertThatThrownBy(() -> second.restore("Q10")).hasMessageContaining("unpause 실패"),
            () -> assertThat(logText()).contains("restore-state-unknown", "unpause-failed")
        );
    }

    @DisplayName("대상이 확인되지 않았으면 pause 하지 않고, 시도가 없으면 복구 명령도 보내지 않는다.")
    @Test
    void noTargetNoCommand() throws Exception {
        FakeCli cli = new FakeCli();
        PauseGuard guard = new PauseGuard(cli, log());

        assertThatThrownBy(() -> guard.pause("Q9")).hasMessageContaining("확인된 대상");
        guard.restore("Q9");
        guard.closeEntryAndRestore("Q9");
        guard.restore("Q9 final");

        assertAll(
            () -> assertThat(cli.commands).isEmpty(),
            () -> assertThat(guard.resolved()).isTrue()
        );
    }

    // ------------------------------------------------------------------ 요청 안 pause 조율·최종 복구

    private RecordingAdmissionRepository startedRecording(MemoryWaiting waiting) {
        RecordingAdmissionRepository recording = new RecordingAdmissionRepository(waiting, log());
        recording.next("Q10", 2L, 1L);
        recording.start(1L, 2L, "w-1");
        return recording;
    }

    private static Duration elapsedLimit() {
        return Duration.ofSeconds(20);
    }

    @DisplayName("느린 pause 중에 HTTP 관찰이 끝나도 복구는 진행 중 pause 의 종료를 기다린 뒤에만 상태를 조회하고, finish 위임은 그대로 이어진다.")
    @Test
    void restoreWaitsForSlowPauseInRequest() throws Exception {
        FakeCli cli = new FakeCli();
        cli.releasePause = new CountDownLatch(1);
        PauseGuard guard = new PauseGuard(cli, log(), Duration.ofSeconds(10));
        guard.target(ID);
        MemoryWaiting waiting = new MemoryWaiting();
        RecordingAdmissionRepository recording = startedRecording(waiting);
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));

        ExecutorService executor = workers.start(2);
        List<String> beforeRelease;
        Future<?> request;
        Future<?> restore;
        try {
            request = executor.submit(() -> recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED));
            assertThat(cli.pauseEntered.await(10, TimeUnit.SECONDS)).isTrue();
            restore = executor.submit(() -> {
                guard.closeEntryAndRestore("Q10");
                return null;
            });
            Thread.sleep(300);
            beforeRelease = List.copyOf(cli.commands);
            cli.releasePause.countDown();
            request.get(elapsedLimit().toSeconds(), TimeUnit.SECONDS);
            restore.get(elapsedLimit().toSeconds(), TimeUnit.SECONDS);
        } finally {
            cli.releasePause.countDown();
            workers.close(executor, elapsedLimit());
        }

        assertAll(
            () -> assertThat(beforeRelease).as("pause 종료 전에는 조회하지 않는다").containsExactly("pause " + ID),
            () -> assertThat(cli.commands).containsExactlyElementsOf(PAUSE_INSPECT_UNPAUSE),
            () -> assertThat(waiting.processing).as("원래 finish 위임").isEmpty(),
            () -> assertThat(guard.resolved()).isTrue(),
            () -> assertThat(logText()).contains("\"event\":\"pause-entry-closed\"", "\"inFlight\":true", "pause-call-end")
        );
    }

    @DisplayName("진입 문이 닫힌 뒤 도착한 callback 은 pause 없이 기록만 하고 정상 반환해 원래 finish 를 막지 않는다.")
    @Test
    void callbackAfterEntryClosedSkipsPause() throws Exception {
        FakeCli cli = new FakeCli();
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target(ID);
        MemoryWaiting waiting = new MemoryWaiting();
        RecordingAdmissionRepository recording = startedRecording(waiting);
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));

        guard.closeEntryAndRestore("Q10");
        CouponAdmissionRepository.FinishOutcome outcome = recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED);

        assertAll(
            () -> assertThat(outcome).isEqualTo(CouponAdmissionRepository.FinishOutcome.APPLIED),
            () -> assertThat(waiting.processing).isEmpty(),
            () -> assertThat(cli.commands).isEmpty(),
            () -> assertThat(guard.resolved()).isTrue(),
            () -> assertThat(logText()).contains("pause-skipped-entry-closed")
        );
    }

    @DisplayName("진행 중 pause 의 종료 신호를 한도 안에 못 보면 불명이다. 가능한 복구는 시도하고, 그 뒤 조회가 false 여도 정리·재개를 막는다.")
    @Test
    void unconfirmedPauseEndStaysUnresolved() throws Exception {
        FakeCli cli = new FakeCli();
        cli.releasePause = new CountDownLatch(1);
        PauseGuard guard = new PauseGuard(cli, log(), Duration.ofMillis(200));
        guard.target(ID);
        MemoryWaiting waiting = new MemoryWaiting();
        RecordingAdmissionRepository recording = startedRecording(waiting);
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));
        AtomicInteger truncated = new AtomicInteger();

        ExecutorService executor = workers.start(1);
        try {
            Future<?> request = executor.submit(() -> recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED));
            assertThat(cli.pauseEntered.await(10, TimeUnit.SECONDS)).isTrue();
            guard.closeEntryAndRestore("Q10");
            cli.pausedState = "false";
            guard.restore("Q10 final");
            cli.releasePause.countDown();
            request.get(elapsedLimit().toSeconds(), TimeUnit.SECONDS);
        } finally {
            cli.releasePause.countDown();
            workers.close(executor, elapsedLimit());
        }
        CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, () -> { }, truncated::incrementAndGet);

        assertAll(
            () -> assertThat(cli.commands).as("불명이어도 가능한 복구는 시도")
                .containsSequence("pause " + ID, "inspect -f {{.State.Paused}} " + ID, "unpause " + ID),
            () -> assertThatThrownBy(guard::requireResolved).hasMessageContaining("종료 신호"),
            () -> assertThat(truncated).as("정리 보류").hasValue(0),
            () -> assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("pause 상태 불명"),
            () -> assertThat(logText()).contains("pause-state-unresolved", "restore-not-paused")
        );
    }

    @DisplayName("요청 안 pause 명령이 실패하면 원격 적용 불명이다. 실제 상태가 paused 가 아니어도 정리·재개를 막는다.")
    @Test
    void failedPauseInRequestStaysUnresolved() throws Exception {
        FakeCli cli = new FakeCli();
        cli.pauseFails = true;
        cli.pausedState = "false";
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target(ID);
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));
        AtomicInteger truncated = new AtomicInteger();

        assertThatThrownBy(() -> recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED)).hasMessageContaining("pause 실패");
        guard.closeEntryAndRestore("Q10");
        CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, () -> { }, truncated::incrementAndGet);

        assertAll(
            () -> assertThat(cli.commands).containsExactly("pause " + ID, "inspect -f {{.State.Paused}} " + ID,
                "inspect -f {{.State.Paused}} " + ID),
            () -> assertThatThrownBy(guard::requireResolved).hasMessageContaining("원격 적용 불명"),
            () -> assertThat(truncated).hasValue(0),
            () -> assertThat(logText()).contains("\"outcome\":\"실패 IllegalStateException\"")
        );
    }

    @DisplayName("서버 요청 종료 뒤 최종 복구도 시도 이력으로 실제 조회를 하며, 같은 상태에서 반복해도 안전하다. 해소된 사례만 정리한다.")
    @Test
    void finalRestoreReinspectsAndRepeatsSafely() throws Exception {
        FakeCli cli = new FakeCli();
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target(ID);
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));
        AtomicInteger truncated = new AtomicInteger();

        recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED);
        guard.closeEntryAndRestore("Q10");
        cli.pausedState = "false";
        guard.restore("Q10 final");
        guard.requireResolved();
        CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, () -> { }, truncated::incrementAndGet);

        String inspect = "inspect -f {{.State.Paused}} " + ID;
        assertAll(
            () -> assertThat(cli.commands).containsExactly("pause " + ID, inspect, "unpause " + ID, inspect, inspect),
            () -> assertThat(truncated).hasValue(1),
            () -> recording.requireNotWithheld()
        );
    }

    @DisplayName("사례 끝에 쓰지 않은 finish callback 을 비워 다음 사례·재개의 finish 가 이전 callback 을 소비하지 않는다.")
    @Test
    void caseEndClearsLeftoverCallback() throws Exception {
        PauseGuard guard = new PauseGuard(new FakeCli(), log());
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        recording.beforeNextFinish(oldCalls::incrementAndGet);

        CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, () -> { }, () -> { });
        recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED);
        recording.beforeNextFinish(newCalls::incrementAndGet);
        recording.start(1L, 3L, "w-2");
        recording.finish(1L, 3L, "w-2", CouponWaitingState.ISSUED);

        assertAll(
            () -> assertThat(oldCalls).hasValue(0),
            () -> assertThat(newCalls).hasValue(1),
            () -> assertThat(logText()).contains("finish-callback-cleared")
        );
    }

    @DisplayName("사례가 정상 종료하지 않았으면 pause 가 없어도 정리를 보류하고, 다음 사례는 준비에서 막히며 그 사례 끝에도 보류가 풀리지 않는다.")
    @Test
    void unfinishedCaseWithholdsAcrossCases() throws Exception {
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        AtomicInteger truncated = new AtomicInteger();

        CouponLocalRedisFaultIntegrationTest.endCase(new PauseGuard(new FakeCli(), log()), recording, false, () -> { }, truncated::incrementAndGet);
        // 다음 사례: 새 guard 로 시작하지만 준비 첫 단계에서 막히므로 정상 종료하지 못한다.
        PauseGuard nextCase = new PauseGuard(new FakeCli(), log());
        assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("정상 종료");
        CouponLocalRedisFaultIntegrationTest.endCase(nextCase, recording, false, () -> { }, truncated::incrementAndGet);

        assertAll(
            () -> assertThat(truncated).hasValue(0),
            () -> assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("정상 종료")
        );
    }

    @DisplayName("HTTP read 한도는 pause 종료 대기와 Redis command timeout 의 합보다 커야 하고, command timeout 을 모르면 시작하지 않는다.")
    @Test
    void timeoutBudget() {
        PauseGuard.requireTimeoutBudget(Duration.ofMillis(200));
        assertAll(
            () -> assertThat(PauseGuard.PAUSE_END_WAIT).isGreaterThan(PauseGuard.CLI_LIMIT),
            () -> assertThatThrownBy(() -> PauseGuard.requireTimeoutBudget(null)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> PauseGuard.requireTimeoutBudget(Duration.ofSeconds(25))).isInstanceOf(IllegalStateException.class)
        );
    }

    // ------------------------------------------------------------------ 기록 실패·interrupt 에도 복구

    /** 정상 기록 파일을 같은 경로의 디렉터리로 바꿔 이후 쓰기를 실패시킨다. */
    private void breakLog() throws Exception {
        Path path = dir.resolve("fault.jsonl");
        Files.deleteIfExists(path);
        Files.createDirectory(path);
    }

    @DisplayName("pause 뒤 실행 기록 쓰기가 실패해도 사례 끝 복구는 inspect·unpause 를 시도하고, 기록 실패를 던지며 정리 0·보류다.")
    @Test
    void logFailureDoesNotSkipRestore() throws Exception {
        FakeCli cli = new FakeCli();
        PauseGuard guard = new PauseGuard(cli, log());
        guard.target(ID);
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));
        recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED);
        recording.beforeNextFinish(() -> { }); // 쓰지 않은 callback 이 남은 상태
        breakLog();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger truncated = new AtomicInteger();

        Throwable thrown = catchThrowable(() ->
            CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, closed::incrementAndGet, truncated::incrementAndGet));

        assertAll(
            () -> assertThat(cli.commands).as("기록 실패에도 복구 시도").containsExactlyElementsOf(PAUSE_INSPECT_UNPAUSE),
            () -> assertThat(thrown).isNotNull().hasMessageContaining("실행 기록 쓰기 실패"),
            () -> assertThat(thrown.getSuppressed()).as("이후 기록 실패도 보존").isNotEmpty(),
            () -> assertThat(closed).hasValue(1),
            () -> assertThat(truncated).as("정리 0").hasValue(0),
            () -> assertThatThrownBy(guard::requireResolved).hasMessageContaining("실행 기록 쓰기 실패"),
            () -> assertThatThrownBy(recording::requireNotWithheld).as("메모리 보류는 기록 실패와 무관").hasMessageContaining("pause 상태 불명")
        );
    }

    @DisplayName("pause 호출 종료 기록이 실패해도 종료 신호는 남아 복구가 대기 한도까지 기다리지 않으며, 기록 실패는 불명이다.")
    @Test
    void pauseEndRecordFailureStillSignalsEnd() throws Exception {
        FakeCli cli = new FakeCli();
        cli.releasePause = new CountDownLatch(1);
        PauseGuard guard = new PauseGuard(cli, log(), Duration.ofSeconds(15));
        guard.target(ID);
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));

        ExecutorService executor = workers.start(1);
        Throwable thrown;
        long elapsedMillis;
        try {
            Future<?> request = executor.submit(() -> recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED));
            assertThat(cli.pauseEntered.await(10, TimeUnit.SECONDS)).isTrue();
            breakLog(); // pause 성공 기록·pause-call-end 기록이 실패한다.
            cli.releasePause.countDown();
            long started = System.nanoTime();
            thrown = catchThrowable(() -> guard.closeEntryAndRestore("Q10"));
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(catchThrowable(() -> request.get(10, TimeUnit.SECONDS)))
                .as("요청 쪽은 finish 기록 실패로 끝남").isNotNull();
        } finally {
            cli.releasePause.countDown();
            workers.close(executor, elapsedLimit());
        }

        assertAll(
            () -> assertThat(elapsedMillis).as("종료 신호로 곧 복구").isLessThan(5_000),
            () -> assertThat(cli.commands).containsExactlyElementsOf(PAUSE_INSPECT_UNPAUSE),
            () -> assertThat(thrown).isNotNull().hasMessageContaining("실행 기록 쓰기 실패"),
            () -> assertThatThrownBy(guard::requireResolved).hasMessageContaining("실행 기록 쓰기 실패")
        );
    }

    @DisplayName("pause 종료 대기 중 interrupt 는 종료 미확인으로 남기고, interrupt 가 끊지 않은 상태로 복구 명령을 실행한 뒤 interrupt 를 되돌린다. 정리 0이다.")
    @Test
    void interruptDuringPauseEndWaitStillRestores() throws Exception {
        FakeCli cli = new FakeCli();
        cli.releasePause = new CountDownLatch(1);
        PauseGuard guard = new PauseGuard(cli, log(), Duration.ofSeconds(15));
        guard.target(ID);
        RecordingAdmissionRepository recording = startedRecording(new MemoryWaiting());
        recording.beforeNextFinish(() -> guard.pauseInRequest("Q10"));
        AtomicInteger truncated = new AtomicInteger();
        AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();

        ExecutorService executor = workers.start(1);
        Thread restorer = new Thread(() -> {
            try {
                guard.closeEntryAndRestore("Q10");
            } catch (Throwable e) {
                restoreFailure.set(e);
            }
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "guard-restorer");
        try {
            Future<?> request = executor.submit(() -> recording.finish(1L, 2L, "w-1", CouponWaitingState.ISSUED));
            assertThat(cli.pauseEntered.await(10, TimeUnit.SECONDS)).isTrue();
            restorer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!logText().contains("pause-entry-closed") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100); // 종료 대기에 들어갈 시간
            restorer.interrupt();
            restorer.join(10_000);
            assertThat(restorer.isAlive()).as("복구 스레드 종료").isFalse();
            List<String> atInterrupt = List.copyOf(cli.commands);

            cli.releasePause.countDown(); // 붙잡은 pause 를 끝내고 요청 worker 를 종료시킨다.
            request.get(10, TimeUnit.SECONDS);
            cli.pausedState = "false";
            guard.restore("Q10 final"); // 서버 요청 종료 뒤 최종 복구도 실제 조회한다.
            CouponLocalRedisFaultIntegrationTest.endCase(guard, recording, true, () -> { }, truncated::incrementAndGet);

            assertAll(
                () -> assertThat(atInterrupt).as("interrupt 뒤에도 복구 시도").containsExactlyElementsOf(PAUSE_INSPECT_UNPAUSE),
                () -> assertThat(cli.interruptedAtRestore.subList(0, 2)).as("복구 CLI 는 interrupt 로 끊기지 않음").containsOnly(false),
                () -> assertThat(interruptRestored).as("interrupt 상태 복원").isTrue(),
                () -> assertThat(restoreFailure.get()).isNull(),
                () -> assertThatThrownBy(guard::requireResolved).hasMessageContaining("interrupt"),
                () -> assertThat(cli.commands).as("최종·사례 끝 복구도 실제 조회").endsWith(
                    "inspect -f {{.State.Paused}} " + ID, "inspect -f {{.State.Paused}} " + ID),
                () -> assertThat(truncated).hasValue(0),
                () -> assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("pause 상태 불명"),
                () -> assertThat(Thread.currentThread().isInterrupted()).as("test 스레드로 새지 않음").isFalse()
            );
        } finally {
            cli.releasePause.countDown();
            restorer.interrupt();
            restorer.join(10_000);
            workers.close(executor, elapsedLimit());
        }
    }

    // ------------------------------------------------------------------ 캐시 장애 opt-in 의 기록 decorator

    /** 호출을 남기고 지정하면 put 을 실패시키는 메모리 캐시 대역. */
    private static final class MemoryCache implements CouponCacheRepository {
        final List<String> puts = Collections.synchronizedList(new ArrayList<>());
        volatile boolean putFails;

        @Override
        public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
            return Optional.empty();
        }

        @Override
        public void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis) {
            if (putFails) {
                throw new IllegalStateException("put timeout(대역)");
            }
            puts.add(userId + ":" + campaignId);
        }

        @Override
        public OptionalLong findSoldOutTtl(Long campaignId) {
            return OptionalLong.of(1_000L);
        }

        @Override
        public void putSoldOutIfAbsent(Long campaignId, long ttlMillis) {
        }
    }

    private static IssuedCoupon issued(long campaignId) {
        return new IssuedCoupon(9L, campaignId, "가을", CouponBenefitType.FIXED_AMOUNT, 1_000L);
    }

    @DisplayName("캐시 기록 decorator 는 지정한 사용자·캠페인의 첫 기발급 put 직전에만 callback 을 한 번 실행하고, 위임 실패만 put 실패로 센다.")
    @Test
    void cacheRecorderRunsHookOnceAndCountsDelegateFailures() throws Exception {
        MemoryCache memory = new MemoryCache();
        RecordingCacheRepository cache = new RecordingCacheRepository(memory, log());
        AtomicInteger hooks = new AtomicInteger();
        cache.beforeFirstIssuedPut(2L, 7L, hooks::incrementAndGet);

        cache.putIssuedIfAbsent(3L, 7L, issued(7L), 1_000L); // 다른 사용자
        cache.putIssuedIfAbsent(2L, 7L, issued(7L), 1_000L);
        memory.putFails = true;
        assertThatThrownBy(() -> cache.putIssuedIfAbsent(2L, 7L, issued(7L), 1_000L)).hasMessageContaining("put timeout");
        cache.findSoldOutTtl(7L);

        assertAll(
            () -> assertThat(hooks).as("첫 해당 put 에서만").hasValue(1),
            () -> assertThat(memory.puts).containsExactly("3:7", "2:7"),
            () -> assertThat(cache.calls("issued-put")).isEqualTo(3),
            () -> assertThat(cache.failures("issued-put")).isEqualTo(1),
            () -> assertThat(cache.calls("sold-out-get")).isEqualTo(1),
            () -> assertThat(cache.lastSoldOutRemainingMillis()).isEqualTo(1_000L),
            () -> assertThat(logText()).contains("cache issued-put-failed IllegalStateException (적용 여부 불명)")
        );
    }

    @DisplayName("캐시 기록 decorator 의 callback 실패는 put 실패와 따로 기록하고 위임하지 않으며 put 실패로 세지 않는다.")
    @Test
    void cacheRecorderSeparatesCallbackFailure() throws Exception {
        MemoryCache memory = new MemoryCache();
        RecordingCacheRepository cache = new RecordingCacheRepository(memory, log());
        cache.beforeFirstIssuedPut(2L, 7L, () -> {
            throw new IllegalStateException("callback 실패(대역)");
        });

        assertThatThrownBy(() -> cache.putIssuedIfAbsent(2L, 7L, issued(7L), 1_000L)).hasMessageContaining("callback 실패");

        assertAll(
            () -> assertThat(memory.puts).isEmpty(),
            () -> assertThat(cache.calls("issued-put")).isZero(),
            () -> assertThat(cache.failures("issued-put")).isZero(),
            () -> assertThat(logText()).contains("issued-put-callback-failed").doesNotContain("issued-put-failed")
        );
    }
}
