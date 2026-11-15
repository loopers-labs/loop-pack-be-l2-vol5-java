package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueGate.Decision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱 메모리 판단이 서버 한 대 안에서는 Redis 판단과 같은 규칙을 지키는지 확인한다. */
class LocalCouponIssueGateTest {
    private static final long CAMPAIGN = 1L;
    private final Instant now = Instant.now();
    private final Instant endAt = now.plusSeconds(3600);
    private final LocalCouponIssueGate gate = new LocalCouponIssueGate();

    @Test
    void unpreparedCampaignIsNotJudged() {
        assertThat(gate.enter(CAMPAIGN, 1L, now).decision()).isEqualTo(Decision.NOT_PREPARED);
        gate.cancel(CAMPAIGN, 1L, "R:unknown");
        assertThat(gate.confirm(CAMPAIGN, 1L, "R:unknown", true)).isFalse();
    }

    @Test
    void reservesOnlyRemainingAndRecognizesUsers() {
        gate.prepare(CAMPAIGN, 1, List.of(9L), endAt, endAt.plusSeconds(3600));

        assertThat(gate.enter(CAMPAIGN, 9L, now).decision()).isEqualTo(Decision.ALREADY_ACCEPTED);
        var entry = gate.enter(CAMPAIGN, 1L, now);
        assertThat(entry.decision()).isEqualTo(Decision.PASSED);
        assertThat(gate.enter(CAMPAIGN, 1L, now).decision()).isEqualTo(Decision.ALREADY_RESERVED);
        assertThat(gate.enter(CAMPAIGN, 2L, now).decision()).isEqualTo(Decision.SOLD_OUT);
        assertThat(gate.enter(CAMPAIGN, 3L, endAt).decision()).isEqualTo(Decision.ENDED);

        assertThat(gate.confirm(CAMPAIGN, 1L, entry.reservation(), true)).isTrue();
        assertThat(gate.enter(CAMPAIGN, 1L, now).decision()).isEqualTo(Decision.ALREADY_ACCEPTED);
    }

    @Test
    void cancelAndExistingResultReturnStockOnlyOnce() {
        gate.prepare(CAMPAIGN, 1, List.of(), endAt, endAt.plusSeconds(3600));
        var first = gate.enter(CAMPAIGN, 1L, now);

        gate.cancel(CAMPAIGN, 1L, first.reservation());
        gate.cancel(CAMPAIGN, 1L, first.reservation());
        var second = gate.enter(CAMPAIGN, 2L, now);
        assertThat(second.decision()).isEqualTo(Decision.PASSED);
        assertThat(gate.enter(CAMPAIGN, 3L, now).decision()).isEqualTo(Decision.SOLD_OUT);

        // DB에 이미 쿠폰이 있던 실행은 차감분만 돌린다.
        assertThat(gate.confirm(CAMPAIGN, 2L, second.reservation(), false)).isTrue();
        assertThat(gate.confirm(CAMPAIGN, 2L, second.reservation(), false)).isFalse();
        assertThat(gate.enter(CAMPAIGN, 3L, now).decision()).isEqualTo(Decision.PASSED);
        assertThat(gate.enter(CAMPAIGN, 4L, now).decision()).isEqualTo(Decision.SOLD_OUT);
    }

    /** 서버 한 대 안에서는 동시에 몰려도 남은 수만큼만 통과한다. */
    @Test
    void concurrentEntriesOnOneServerNeverExceedRemaining() throws Exception {
        gate.prepare(CAMPAIGN, 10, List.of(), endAt, endAt.plusSeconds(3600));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            List<Future<Decision>> results = new ArrayList<>();
            for (long userId = 1; userId <= 100; userId++) {
                long id = userId;
                results.add(executor.submit(() -> {
                    start.await();
                    return gate.enter(CAMPAIGN, id, now).decision();
                }));
            }
            start.countDown();
            long passed = 0;
            for (Future<Decision> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == Decision.PASSED) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }
}
