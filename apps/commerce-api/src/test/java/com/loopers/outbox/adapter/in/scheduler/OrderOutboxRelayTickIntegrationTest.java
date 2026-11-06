package com.loopers.outbox.adapter.in.scheduler;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * relay 주기 (docs/week7/plan.md D-1 "tick 1초", 4단계 fixedDelay). 다른 scheduler 테스트는 자동 tick을 1시간으로 미루고 tick()을 직접 부르므로,
 * <b>Spring이 실제로 몇 초마다 relay를 부르는지</b>는 여기서만 본다. tick 값은 덮어쓰지 않는다 — application.yml의 {@code outbox.relay.tick: 1s} 그대로.
 * <p>
 * relay는 시작 시각만 적는 가짜로 바꾼다(실제 relay는 행을 고르고 broker로 보낸다 — 주기와 무관). 시각은 System.nanoTime(단조 시계).
 * fixedDelay는 "앞 회차가 <b>끝난 뒤</b> 1초"다 — 회차가 오래 걸리면 시작 간격도 그만큼 길어지고, 회차가 겹치지 않는다.
 */
@SpringBootTest(properties = "outbox.relay.scheduler.enabled=true")
class OrderOutboxRelayTickIntegrationTest {

    private static final Duration TICK = Duration.ofSeconds(1);
    /** 스케줄러 스레드가 깨어나는 지연 등 흔들림 허용치 */
    private static final Duration JITTER = Duration.ofMillis(300);

    @Autowired
    private TimedRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS shedlock (
              name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL,
              locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), locked_by VARCHAR(255) NOT NULL,
              PRIMARY KEY (name))""");
        jdbcTemplate.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, UTC_TIMESTAMP(3), 'test-reset') "
            + "ON DUPLICATE KEY UPDATE lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, locked_by = 'test-reset'", OrderOutboxRelayScheduler.LOCK_NAME);
    }

    @DisplayName("relay는 1초마다 돈다 — 회차가 금방 끝나면 시작 간격은 1초(+흔들림 0.3초 이내)다.")
    @Test
    void relayRunsEverySecond() {
        // arrange: 회차가 바로 끝난다
        relay.reset(Duration.ZERO);

        // act: 실제 scheduler가 부르게 둔다
        await().atMost(Duration.ofSeconds(10)).until(() -> relay.starts().size() >= 5);

        // assert
        for (Duration gap : gaps(relay.starts())) {
            assertThat(gap).isBetween(TICK, TICK.plus(JITTER));
        }
    }

    @DisplayName("fixedDelay — 회차가 1.5초 걸리면 다음 회차는 끝난 뒤 1초에 시작한다(시작 간격 2.5초). 회차는 겹치지 않는다.")
    @Test
    void nextRoundWaitsForPreviousRoundToFinish() {
        // arrange: 회차 하나가 1.5초 걸린다 (예: broker 응답이 느림)
        Duration work = Duration.ofMillis(1500);
        relay.reset(work);

        // act
        await().atMost(Duration.ofSeconds(15)).until(() -> relay.starts().size() >= 4);

        // assert
        for (Duration gap : gaps(relay.starts())) {
            assertThat(gap).isBetween(work.plus(TICK), work.plus(TICK).plus(JITTER));
        }
        assertThat(relay.maxConcurrent()).isEqualTo(1);
    }

    /** 첫 기록은 reset 직전에 시작된 회차일 수 있어 버리고, 그 뒤 연속한 시작 사이 간격만 본다 */
    private static List<Duration> gaps(List<Long> starts) {
        List<Duration> gaps = new ArrayList<>();
        for (int i = 2; i < starts.size(); i++) {
            gaps.add(Duration.ofNanos(starts.get(i) - starts.get(i - 1)));
        }
        return gaps;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        TimedRelay timedRelay() {
            return new TimedRelay();
        }
    }

    /** 시작 시각을 적고, 정한 시간만큼 걸린 뒤 "고른 행 0"으로 끝나는 가짜 relay */
    static class TimedRelay implements OrderOutboxRelayUseCase {
        private final List<Long> starts = new CopyOnWriteArrayList<>();
        private volatile Duration work = Duration.ZERO;
        private int running;
        private int maxConcurrent;

        void reset(Duration work) {
            this.work = work;
            starts.clear();
            synchronized (this) {
                maxConcurrent = 0;
            }
        }

        List<Long> starts() {
            return List.copyOf(starts);
        }

        synchronized int maxConcurrent() {
            return maxConcurrent;
        }

        @Override
        public RelayResult relayOnce() {
            starts.add(System.nanoTime());
            synchronized (this) {
                maxConcurrent = Math.max(maxConcurrent, ++running);
            }
            try {
                Thread.sleep(work.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (this) {
                    running--;
                }
            }
            return new RelayResult(0, 0, null);
        }
    }
}
