package com.loopers.support.cache;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheSingleFlightTest {
    private final CacheSingleFlight flights = new CacheSingleFlight();
    private final CacheSingleFlight.Key key = new CacheSingleFlight.Key("stocks", "ids=1:rev=a");

    @Test
    @DisplayName("같은 키의 동시 miss는 원본 실행 하나를 기다리고 응답 객체를 공유하지 않는다")
    void coalescesAndDoesNotShareResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var owner = new FutureTask<>(() -> flights.execute(key, () -> {
            calls.incrementAndGet(); entered.countDown(); waitFor(release); return "owner-response";
        }));
        Thread.ofPlatform().start(owner);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var follower = new FutureTask<>(() -> flights.execute(key, () -> {
            calls.incrementAndGet(); return "wrong";
        }));
        Thread thread = Thread.ofPlatform().start(follower);
        try {
            awaitWaiting(thread);
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(owner.get(5, TimeUnit.SECONDS).value()).isEqualTo("owner-response");
        assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo(new CacheSingleFlight.Result<>(false, null));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(flights.execute(key, () -> "next").value()).isEqualTo("next");
    }

    @Test
    @DisplayName("다른 revision과 다른 캐시 종류는 실행 중인 재채움을 기다리지 않는다")
    void isolatesRevisionAndCacheName() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner = new FutureTask<>(() -> flights.execute(key, () -> {
            entered.countDown(); waitFor(release); return "old";
        }));
        Thread.ofPlatform().start(owner);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(flights.execute(new CacheSingleFlight.Key("stocks", "ids=1:rev=b"), () -> "new").value()).isEqualTo("new");
            assertThat(flights.execute(new CacheSingleFlight.Key("counts", "ids=1:rev=a"), () -> "count").value()).isEqualTo("count");
        } finally { release.countDown(); }
        owner.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("재채움 실패도 대기를 해제하고 다른 요청에 오류를 공유하지 않으며 다음 재시도가 가능하다")
    void releasesFollowersAfterFailureAndRemovesFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IllegalStateException("origin failed");
        var owner = new FutureTask<>(() -> flights.execute(key, () -> {
            entered.countDown(); waitFor(release); throw failure;
        }));
        Thread.ofPlatform().start(owner);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var follower = new FutureTask<>(() -> flights.execute(key, () -> "wrong"));
        Thread thread = Thread.ofPlatform().start(follower);
        try { awaitWaiting(thread); } finally { release.countDown(); }
        assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS)).hasCause(failure);
        assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo(new CacheSingleFlight.Result<>(false, null));
        assertThat(flights.execute(key, () -> "recovered").value()).isEqualTo("recovered");
    }

    static void waitFor(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.getState() != Thread.State.WAITING && thread.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }
}
