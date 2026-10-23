package com.loopers.infrastructure.payment;

import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.PgFixtureServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 어댑터 테스트마다 새 PG fixture 와 호출 스레드를 띄우고, 끝나면 둘 다 종료됐는지 확인한다. */
abstract class PgAdapterTestSupport {

    static final Duration OBSERVE_DEADLINE = Duration.ofSeconds(5);
    /** 붙잡은 요청은 client 응답 timeout(1초)이 지나면 끝나므로 그 전에 다음 단계를 진행하도록 짧게 관찰한다. */
    static final Duration FAST_POLL = Duration.ofMillis(5);
    static final Duration TASK_DEADLINE = Duration.ofSeconds(15);

    PgFixtureServer pg;
    ExecutorService callers;

    @BeforeEach
    void startPgFixture() {
        pg = PgFixtureServer.start();
        callers = Executors.newFixedThreadPool(10);
    }

    @AfterEach
    void stopPgFixture() throws InterruptedException {
        try {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(TASK_DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("호출 스레드 종료").isTrue();
        } finally {
            pg.close();
            assertThat(pg.isTerminated()).as("PG fixture 종료").isTrue();
        }
    }

    /** 클라이언트가 응답을 받은 뒤에도 fixture handler 의 journal 완료는 조금 늦을 수 있어 기한 안에 기다린다. */
    void awaitHandlersFinished() {
        await().atMost(OBSERVE_DEADLINE).until(() -> {
            FixtureState state = pg.state();
            return state.activeRequests() == 0
                && state.journal().stream().allMatch(entry -> entry.completedAt() != null);
        });
    }
}
