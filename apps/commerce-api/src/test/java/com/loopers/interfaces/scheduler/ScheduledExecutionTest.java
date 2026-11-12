package com.loopers.interfaces.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습용: relay 스케줄러가 쓰는 {@code @Scheduled} 가 회차를 어떻게 이어 붙이는지 시간표로 관찰한다.
 * {@code @Scheduled(fixedDelay = n)} 은 {@code scheduleWithFixedDelay}, {@code fixedRate} 는 {@code scheduleAtFixedRate} 호출과 같고, 기본 스레드는 1개다.
 */
@Tag("experiment")
class ScheduledExecutionTest {

    /** 한 회차가 실행된 구간. 예: "300~500ms". */
    record Run(long startMs, long endMs) {
        @Override
        public String toString() {
            return startMs + "~" + endMs + "ms";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @DisplayName("fixedDelay: 이전 회차가 끝난 뒤 100ms 쉬고 다음 회차를 시작한다. 회차는 겹치지 않는다.")
    @Test
    void startsAfterPreviousEnds_whenFixedDelay() {
        // given: @Scheduled 의 기본과 같은 스레드 1개짜리 스케줄러
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        List<Run> runs = new CopyOnWriteArrayList<>();
        long origin = System.currentTimeMillis();

        // when: 200ms 걸리는 작업을 fixedDelay 100ms 로 등록한다. (= @Scheduled(fixedDelay = 100))
        scheduler.scheduleWithFixedDelay(() -> {
            long start = System.currentTimeMillis() - origin;
            sleep(200);
            runs.add(new Run(start, System.currentTimeMillis() - origin));
        }, Duration.ofMillis(100));
        sleep(1_000);
        scheduler.shutdown();

        // then: 회차 구간 [0~200ms, 300~500ms, 600~800ms] — 첫 회차가 끝나고 100ms 쉰 뒤 둘째 회차가 시작한다.
        System.out.println("fixedDelay 회차: " + runs);
        long rest = runs.get(1).startMs() - runs.get(0).endMs();
        assertThat(rest).as("첫 회차가 끝나고 둘째 회차가 시작하기까지 쉰 시간").isBetween(90L, 150L);
    }

    @DisplayName("fixedRate: 시작 시각 기준으로 100ms마다 실행하려 한다. 작업이 200ms로 더 길면 겹치지는 않고, 쉬지 않고 바로 다음 회차를 시작한다.")
    @Test
    void startsRightAfterPreviousEnds_whenFixedRateIsShorterThanJob() {
        // given: @Scheduled 의 기본과 같은 스레드 1개짜리 스케줄러
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        List<Run> runs = new CopyOnWriteArrayList<>();
        long origin = System.currentTimeMillis();

        // when: 200ms 걸리는 작업을 fixedRate 100ms 로 등록한다. (= @Scheduled(fixedRate = 100))
        scheduler.scheduleAtFixedRate(() -> {
            long start = System.currentTimeMillis() - origin;
            sleep(200);
            runs.add(new Run(start, System.currentTimeMillis() - origin));
        }, Duration.ofMillis(100));
        sleep(1_000);
        scheduler.shutdown();

        // then: 회차 구간 [0~200ms, 200~400ms, 400~600ms] — 첫 회차가 끝나자마자 둘째 회차가 시작한다. 겹치지는 않는다.
        System.out.println("fixedRate 회차: " + runs);
        long rest = runs.get(1).startMs() - runs.get(0).endMs();
        assertThat(rest).as("첫 회차가 끝나고 둘째 회차가 시작하기까지 쉰 시간").isBetween(0L, 30L);
    }

    @DisplayName("회차에서 예외가 나도 스케줄은 멈추지 않고 다음 회차를 실행한다.")
    @Test
    void keepsRunning_whenJobThrows() {
        // given: @Scheduled 의 기본과 같은 스레드 1개짜리 스케줄러
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        List<Long> startedAtMs = new CopyOnWriteArrayList<>();
        long origin = System.currentTimeMillis();

        // when: 매 회차 예외를 던지는 작업을 fixedDelay 100ms 로 등록한다.
        scheduler.scheduleWithFixedDelay(() -> {
            startedAtMs.add(System.currentTimeMillis() - origin);
            throw new IllegalStateException("회차 실패");
        }, Duration.ofMillis(100));
        sleep(500);
        scheduler.shutdown();

        // then: 시작 시각 [0, 100, 200, 300, 400ms] — 예외는 로그로만 남고 다음 회차가 온다.
        System.out.println("예외가 나는 회차의 시작 시각(ms): " + startedAtMs);
        assertThat(startedAtMs).hasSizeGreaterThanOrEqualTo(3);
    }
}
