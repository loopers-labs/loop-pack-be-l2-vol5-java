package com.loopers.outbox.adapter.in.scheduler;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase.RelayResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 겹침 방지 (docs/week7/plan.md 4단계, 핵심 4). ShedLock은 outbox 행이 아니라 작업 이름 "order-outbox-relay" 한 행에 거는 잠금이다.
 * 잠금을 쥔 실행이 relayOnce() 한 번 전체(행 고르기 → 행마다 TX1·send·TX2)를 마칠 때까지, 다른 실행은 기다리지 않고 건너뛴다.
 * <p>
 * relay는 가짜(MockitoBean)로 바꿔 "회차가 몇 번 돌았나"만 센다. 자동 tick은 1시간 뒤로 미뤄 두고 tick()을 직접 부른다.
 * shedlock 테이블은 엔티티가 아니라 ddl-auto가 만들지 않으므로 여기서 만든다 (운영·로컬은 docs/week7/schema.sql).
 * <p>
 * 잠금 행은 지우지 않고 "기한이 지난 잠금"으로 되돌린다. ShedLock은 한 번 만든 잠금 행을 기억하고 그다음부터는 UPDATE만 하므로,
 * 앱이 도는 중에 행을 지우면 잠금을 다시는 얻지 못한다(재시작 전까지). 시각은 ShedLock이 MySQL에서 쓰는 UTC_TIMESTAMP로 맞춘다.
 */
@SpringBootTest(properties = {"outbox.relay.scheduler.enabled=true", "outbox.relay.tick=1h"})
class OrderOutboxRelaySchedulerIntegrationTest {

    @Autowired
    private OrderOutboxRelayScheduler scheduler;

    @MockitoBean
    private OrderOutboxRelayUseCase relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS shedlock (
              name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL,
              locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), locked_by VARCHAR(255) NOT NULL,
              PRIMARY KEY (name))""");
        expireLock();
        when(relay.relayOnce()).thenReturn(new RelayResult(0, 0, null));
    }

    /** 잠금 행이 있게 하되 기한은 지났게 한다 — 아무도 쥐고 있지 않은 상태 */
    private void expireLock() {
        jdbcTemplate.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, UTC_TIMESTAMP(3), 'test-reset') "
            + "ON DUPLICATE KEY UPDATE lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND, locked_by = 'test-reset'", OrderOutboxRelayScheduler.LOCK_NAME);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        expireLock();
    }

    @DisplayName("핵심 4 회차가 도는 중에 들어온 tick(수동 실행·다른 앱)은 기다리지 않고 건너뛴다 — relay는 한 번만 돈다.")
    @Test
    void overlappingTickIsSkipped() throws Exception {
        // arrange: 첫 회차가 relay 안에서 멈춰 있게 한다
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(relay.relayOnce()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new RelayResult(0, 0, null);
        });
        Future<?> first = executor.submit(scheduler::tick);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // act: 첫 회차가 잠금을 쥔 동안 두 번째 tick
        long started = System.nanoTime();
        scheduler.tick();
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // assert: 두 번째는 relay에 들어가지 않고 바로 돌아왔다
        verify(relay, times(1)).relayOnce();
        assertThat(waitedMillis).isLessThan(2_000);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @DisplayName("핵심 4 회차가 끝나면 잠금이 풀려 다음 tick은 다시 돈다.")
    @Test
    void lockIsReleasedAfterRound() {
        // act
        scheduler.tick();
        scheduler.tick();

        // assert
        verify(relay, times(2)).relayOnce();
    }

    @DisplayName("핵심 4 잠금은 DB에 있다 — 다른 앱(dead-app)이 잠금을 쥐고 있으면 이 앱은 relay를 돌리지 않고, 그 잠금 기한이 지나면 가져간다.")
    @Test
    void lockHeldByAnotherInstanceUntilItExpires() {
        // arrange: 다른 앱이 1시간 동안 잠금을 쥔 채 죽었다
        jdbcTemplate.update("UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) + INTERVAL 1 HOUR, locked_at = UTC_TIMESTAMP(3), locked_by = 'dead-app' WHERE name = ?",
            OrderOutboxRelayScheduler.LOCK_NAME);

        // act & assert: 기한 전에는 돌지 않는다
        scheduler.tick();
        verify(relay, never()).relayOnce();

        // act & assert: 기한이 지나면(= lockAtMostFor 경과) 이 앱이 가져가 돈다
        jdbcTemplate.update("UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE name = ?", OrderOutboxRelayScheduler.LOCK_NAME);
        scheduler.tick();
        verify(relay, times(1)).relayOnce();
    }
}
