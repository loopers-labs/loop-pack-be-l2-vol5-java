package com.loopers.infrastructure.coupon.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.coupon.queue.CouponRequestQueue.SubmitOutcome;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RedisCouponAdmissionOrderingTest {
    @Test
    @SuppressWarnings("unchecked")
    void 진행중인_접수는_끝나지만_중단을_요청한_뒤_시작하는_신규_접수는_닫힌_상태를_본다() throws Exception {
        var state = new CouponQueueWorkerState(true);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                    String userId = invocation.getArgument(3);
                    String gate = invocation.getArgument(6);
                    if ("1".equals(userId)) {
                        assertThat(gate).isEqualTo("1");
                        firstStarted.countDown();
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("접수 동기화 테스트 시간 초과");
                        }
                        return "ACCEPTED:request-1";
                    }
                    assertThat(gate).isEqualTo("0");
                    return "PAUSED";
                });
        var queue = new RedisCouponQueue(redis, 100, "coupon:{test}", state);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> queue.submit(1, 1));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var paused = executor.submit(state::pause);

            // 첫 Redis 접수가 진행 중이어도 중단 플래그는 즉시 닫힌다.
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !state.acceptingNewRequests());
            assertThat(paused.isDone()).isFalse();
            var second = executor.submit(() -> queue.submit(1, 2));
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
            paused.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(SubmitOutcome.PAUSED);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 접수_안에서_Redis_오류가_나도_잠금을_재진입해_수용을_닫는다() {
        var state = new CouponQueueWorkerState(true);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("Redis failure"));
        var queue = new RedisCouponQueue(redis, 100, "coupon:{test}", state);

        assertThatThrownBy(() -> queue.submit(1, 1))
                .isInstanceOfSatisfying(CoreException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
        assertThat(state.acceptingNewRequests()).isFalse();
    }
}
