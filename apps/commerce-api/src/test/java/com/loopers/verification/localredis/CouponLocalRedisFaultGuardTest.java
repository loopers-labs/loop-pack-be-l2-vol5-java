package com.loopers.verification.localredis;

import com.loopers.domain.coupon.TokenOnlyAdmissionRepository;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.FaultLog;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.PauseGuard;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.RecordingAdmissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 로컬 Redis 장애 테스트의 실패 처리 장치를 Docker·Redis 없이 대역으로 확인한다. 실제 컨테이너를 멈추지 않는다.
 * 장애 테스트 본체와 달리 환경변수 없이 일반 실행에서 돈다.
 */
@DisplayName("장애 테스트의 정리 보류는 컨텍스트 동안 유지되고, pause 결과가 불명해도 확인된 컨테이너의 실제 상태로 복구한다.")
class CouponLocalRedisFaultGuardTest {

    @TempDir
    Path dir;

    private static final class MemoryPermits implements TokenOnlyAdmissionRepository {
        private final Set<String> permits = ConcurrentHashMap.newKeySet();

        @Override
        public Acquisition tryAcquire(String token, int capacity) {
            if (permits.contains(token) || permits.size() < capacity) {
                permits.add(token);
                return Acquisition.ACQUIRED;
            }
            return Acquisition.FULL;
        }

        @Override
        public long release(String token) {
            return permits.remove(token) ? 1 : 0;
        }
    }

    /** 명령을 기록하고 지정한 응답·실패를 돌려주는 docker CLI 대역. */
    private static final class FakeCli implements PauseGuard.Cli {
        final List<String> commands = new ArrayList<>();
        boolean pauseFails;
        String pausedState = "true";
        boolean inspectFails;
        boolean unpauseFails;

        @Override
        public String run(String... args) throws Exception {
            commands.add(String.join(" ", args));
            switch (args[0]) {
                case "pause" -> {
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

    @DisplayName("실패한 요청이 token 을 만들지 않았어도 보류하면 다음 사례가 막히고, 다른 요청의 성공·빈 Set 으로 풀리지 않는다.")
    @Test
    void withheldContextBlocksNextCase() throws Exception {
        MemoryPermits permits = new MemoryPermits();
        RecordingAdmissionRepository recording = new RecordingAdmissionRepository(permits, log());

        recording.withhold("Q9 응답 없음, token 없음(대역)");
        recording.next("Q10", 2L, 1L);
        recording.tryAcquire("other", 3);
        recording.release("other");

        assertAll(
            () -> assertThat(permits.permits).isEmpty(),
            () -> assertThatThrownBy(recording::requireNotWithheld).hasMessageContaining("Q9 응답 없음"),
            () -> assertThat(logText()).contains("context-withheld")
        );
    }

    @DisplayName("보류가 없으면 다음 사례가 시작할 수 있다.")
    @Test
    void notWithheldAllowsNextCase() {
        RecordingAdmissionRepository recording = new RecordingAdmissionRepository(new MemoryPermits(), log());

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

        assertThat(cli.commands).isEmpty();
    }
}
