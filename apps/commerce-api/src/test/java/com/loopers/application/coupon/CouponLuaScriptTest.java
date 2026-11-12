package com.loopers.application.coupon;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponQueuePort;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponLuaScriptTest {

    @Autowired CouponQueuePort queuePort;
    @Autowired RedisCleanUp redisCleanUp;

    private static final long CAMPAIGN = 100L;
    private static final long HB_TIMEOUT = 30L;
    private static final long ADMIT_TTL = 30L;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("enqueue: 새 사용자를 대기열에 등록한다.")
    @Test
    void enqueue_newUser_returnsEnqueued() {
        CouponQueuePort.EnqueueResult result = queuePort.enqueue(CAMPAIGN, 1L, 10);
        assertThat(result).isEqualTo(CouponQueuePort.EnqueueResult.ENQUEUED);
    }

    @DisplayName("enqueue: 이미 등록된 사용자는 ALREADY_QUEUED를 반환한다.")
    @Test
    void enqueue_duplicate_returnsAlreadyQueued() {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        CouponQueuePort.EnqueueResult result = queuePort.enqueue(CAMPAIGN, 1L, 10);
        assertThat(result).isEqualTo(CouponQueuePort.EnqueueResult.ALREADY_QUEUED);
    }

    @DisplayName("enqueue: 대기열이 가득 차면 QUEUE_FULL을 반환한다.")
    @Test
    void enqueue_full_returnsQueueFull() {
        queuePort.enqueue(CAMPAIGN, 1L, 2);
        queuePort.enqueue(CAMPAIGN, 2L, 2);
        CouponQueuePort.EnqueueResult result = queuePort.enqueue(CAMPAIGN, 3L, 2);
        assertThat(result).isEqualTo(CouponQueuePort.EnqueueResult.QUEUE_FULL);
    }

    @DisplayName("promote: 대기열 사용자를 admitted로 옮긴다.")
    @Test
    void promote_movesUsersToAdmitted() {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        queuePort.enqueue(CAMPAIGN, 2L, 10);

        int promoted = queuePort.promote(CAMPAIGN, 5, HB_TIMEOUT, ADMIT_TTL);
        assertThat(promoted).isEqualTo(2);

        CouponQueuePort.QueueStatusResult s1 = queuePort.status(CAMPAIGN, 1L, ADMIT_TTL);
        assertThat(s1.status()).isEqualTo(CouponQueuePort.QueueStatus.ADMITTED);
    }

    @DisplayName("promote: capacity 한도를 초과하여 옮기지 않는다.")
    @Test
    void promote_respectsCapacity() {
        for (long i = 1; i <= 5; i++) {
            queuePort.enqueue(CAMPAIGN, i, 10);
        }
        int promoted = queuePort.promote(CAMPAIGN, 2, HB_TIMEOUT, ADMIT_TTL);
        assertThat(promoted).isEqualTo(2);
    }

    @DisplayName("promote: 하트비트가 없는 사용자는 대기열에서 제거된다.")
    @Test
    void promote_evictsDeadHeartbeats() throws InterruptedException {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        Thread.sleep(3100);
        int promoted = queuePort.promote(CAMPAIGN, 5, 3L, ADMIT_TTL);
        assertThat(promoted).isZero();

        CouponQueuePort.QueueStatusResult s = queuePort.status(CAMPAIGN, 1L, ADMIT_TTL);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.NONE);
    }

    @DisplayName("start: admitted 사용자는 STARTED를 반환한다.")
    @Test
    void start_admitted_returnsStarted() {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        queuePort.promote(CAMPAIGN, 5, HB_TIMEOUT, ADMIT_TTL);

        CouponQueuePort.StartResult result = queuePort.start(CAMPAIGN, 1L, ADMIT_TTL);
        assertThat(result).isEqualTo(CouponQueuePort.StartResult.STARTED);

        CouponQueuePort.QueueStatusResult s = queuePort.status(CAMPAIGN, 1L, ADMIT_TTL);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.ACTIVE);
    }

    @DisplayName("start: admitted 상태가 아니면 NOT_ADMITTED를 반환한다.")
    @Test
    void start_notAdmitted_returnsNotAdmitted() {
        CouponQueuePort.StartResult result = queuePort.start(CAMPAIGN, 99L, ADMIT_TTL);
        assertThat(result).isEqualTo(CouponQueuePort.StartResult.NOT_ADMITTED);
    }

    @DisplayName("start: admitTtl이 경과하면 EXPIRED를 반환한다.")
    @Test
    void start_expired_returnsExpired() throws InterruptedException {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        queuePort.promote(CAMPAIGN, 5, HB_TIMEOUT, ADMIT_TTL);
        Thread.sleep(3100);

        CouponQueuePort.StartResult result = queuePort.start(CAMPAIGN, 1L, 3L);
        assertThat(result).isEqualTo(CouponQueuePort.StartResult.EXPIRED);
    }

    @DisplayName("release: active 사용자를 제거한다.")
    @Test
    void release_removesFromActive() {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        queuePort.promote(CAMPAIGN, 5, HB_TIMEOUT, ADMIT_TTL);
        queuePort.start(CAMPAIGN, 1L, ADMIT_TTL);

        queuePort.release(CAMPAIGN, 1L);
        CouponQueuePort.QueueStatusResult s = queuePort.status(CAMPAIGN, 1L, ADMIT_TTL);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.NONE);
    }

    @DisplayName("release는 멱등하게 동작한다.")
    @Test
    void release_idempotent() {
        queuePort.release(CAMPAIGN, 999L);
        queuePort.release(CAMPAIGN, 999L);
    }

    @DisplayName("status: 대기열 사용자의 순서를 반환한다.")
    @Test
    void status_queued_returnsPosition() {
        queuePort.enqueue(CAMPAIGN, 1L, 10);
        queuePort.enqueue(CAMPAIGN, 2L, 10);

        CouponQueuePort.QueueStatusResult s = queuePort.status(CAMPAIGN, 2L, ADMIT_TTL);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.QUEUED);
        assertThat(s.position()).isEqualTo(2);
    }

    @DisplayName("status: 등록되지 않은 사용자는 NONE을 반환한다.")
    @Test
    void status_unknown_returnsNone() {
        CouponQueuePort.QueueStatusResult s = queuePort.status(CAMPAIGN, 999L, ADMIT_TTL);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.NONE);
    }

    @DisplayName("promote: admitted와 active 합산이 capacity를 초과하지 않는다.")
    @Test
    void promote_atomic_capacityNotExceeded() {
        for (long i = 1; i <= 10; i++) {
            queuePort.enqueue(CAMPAIGN, i, 20);
        }
        queuePort.promote(CAMPAIGN, 3, HB_TIMEOUT, ADMIT_TTL);
        queuePort.start(CAMPAIGN, 1L, ADMIT_TTL);

        // admitted={2,3}, active={1}: free = 3 - 2 - 1 = 0 → no additional promotion
        int promoted = queuePort.promote(CAMPAIGN, 3, HB_TIMEOUT, ADMIT_TTL);
        assertThat(promoted).isEqualTo(0);
    }
}
