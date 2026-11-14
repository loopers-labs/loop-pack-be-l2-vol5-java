package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueClaim.Claimed;
import com.loopers.application.coupon.CouponIssueQueueResubmission;
import com.loopers.application.coupon.CouponIssueQueueResubmission.Changed;
import com.loopers.application.coupon.CouponIssueQueueResubmission.Full;
import com.loopers.application.coupon.CouponIssueQueueResubmission.Requeued;
import com.loopers.application.coupon.CouponIssueQueueResubmission.Succeeded;
import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RedisCouponIssueQueueResubmitIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 9004L;
    private static final int TIMEOUT_SECONDS = 10;

    @Autowired private CouponIssueQueue queue;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanUpQueueKeys() {
        redisTemplate.delete(List.of(RedisCouponIssueQueue.WAITING_KEY, RedisCouponIssueQueue.PROCESSING_KEY,
            RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }

    @DisplayName("시스템 실패 작업을 같은 작업 ID로 다시 대기시키고 사유와 이전 실행 token을 비운다.")
    @Test
    void requeuesSystemFailureWithSameRequestId() {
        CouponIssueRequest request = failedRequest(901L, "token-901", CouponIssueRequestReason.DB_ERROR);

        CouponIssueQueueResubmission result = queue.requeueFailed(request.getRequestId(),
            CouponIssueRequestReason.DB_ERROR, 20);

        assertThat(result).isInstanceOfSatisfying(Requeued.class, requeued -> {
            CouponIssueRequest stored = requeued.request();
            assertThat(stored.getRequestId()).isEqualTo(request.getRequestId());
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
            assertThat(stored.getReason()).isNull();
            assertThat(stored.getExecutionToken()).isNull();
            assertThat(stored.getCouponId()).isNull();
            assertThat(stored.getRequestedAt()).isEqualTo(REQUESTED_AT);
        });
        assertThat(waitingIds()).containsExactly(request.getRequestId());
        assertThat(processingIds()).isEmpty();
        assertThat(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, 901L))
            .hasValueSatisfying(stored -> assertThat(stored.getRequestId()).isEqualTo(request.getRequestId()));
    }

    @DisplayName("다시 대기시키는 작업도 대기 List 끝에 넣는다.")
    @Test
    void appendsRequeuedRequestToEndOfWaitingList() {
        CouponIssueRequest failed = failedRequest(902L, "token-902", CouponIssueRequestReason.INTERNAL_ERROR);
        CouponIssueRequest waiting = CouponIssueRequest.create(CAMPAIGN_ID, 903L, REQUESTED_AT);
        queue.submit(waiting, 20);

        queue.requeueFailed(failed.getRequestId(), CouponIssueRequestReason.INTERNAL_ERROR, 20);

        assertThat(waitingIds()).containsExactly(waiting.getRequestId(), failed.getRequestId());
    }

    @DisplayName("미완료 한도가 차 있으면 다시 대기시키지 않고 실패 상태를 유지한다.")
    @Test
    void keepsFailureWhenCapacityIsFull() {
        CouponIssueRequest failed = failedRequest(904L, "token-904", CouponIssueRequestReason.DB_ERROR);
        queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 905L, REQUESTED_AT), 20);

        CouponIssueQueueResubmission result = queue.requeueFailed(failed.getRequestId(),
            CouponIssueRequestReason.DB_ERROR, 1);

        assertThat(result).isInstanceOfSatisfying(Full.class,
            full -> assertThat(full.request().getStatus()).isEqualTo(CouponIssueRequestStatus.FAILED));
        assertThat(queue.findById(failed.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getReason()).isEqualTo(CouponIssueRequestReason.DB_ERROR));
    }

    @DisplayName("사유가 다르거나 이미 다른 상태면 손대지 않고 현재 작업을 돌려준다.")
    @Test
    void returnsChangedWhenStateOrReasonDiffers() {
        CouponIssueRequest failed = failedRequest(906L, "token-906", CouponIssueRequestReason.INTERNAL_ERROR);
        CouponIssueRequest waiting = CouponIssueRequest.create(CAMPAIGN_ID, 907L, REQUESTED_AT);
        queue.submit(waiting, 20);

        assertThat(queue.requeueFailed(failed.getRequestId(), CouponIssueRequestReason.DB_ERROR, 20))
            .isInstanceOfSatisfying(Changed.class,
                changed -> assertThat(changed.request().getReason()).isEqualTo(CouponIssueRequestReason.INTERNAL_ERROR));
        assertThat(queue.requeueFailed(waiting.getRequestId(), CouponIssueRequestReason.DB_ERROR, 20))
            .isInstanceOfSatisfying(Changed.class,
                changed -> assertThat(changed.request().getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING));
        assertThat(waitingIds()).containsExactly(waiting.getRequestId());
    }

    @DisplayName("같은 작업을 동시에 다시 접수해도 대기 항목은 하나만 생긴다.")
    @Test
    void requeuesOnceUnderConcurrentResubmissions() throws Exception {
        CouponIssueRequest failed = failedRequest(908L, "token-908", CouponIssueRequestReason.DB_ERROR);

        List<CouponIssueQueueResubmission> results = requeueConcurrently(failed.getRequestId(), 10);

        assertThat(results).filteredOn(Requeued.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(Changed.class::isInstance).hasSize(9);
        assertThat(waitingIds()).containsExactly(failed.getRequestId());
    }

    @DisplayName("재접수 중 쿠폰을 확인하면 같은 작업을 성공으로 바꾸고 어떤 List에도 넣지 않는다.")
    @Test
    void marksFailedRequestSucceededWithExistingCoupon() {
        CouponIssueRequest failed = failedRequest(909L, "token-909", CouponIssueRequestReason.DB_ERROR);

        CouponIssueQueueResubmission result = queue.markFailedSucceeded(failed.getRequestId(),
            CouponIssueRequestReason.DB_ERROR, 2001L);

        assertThat(result).isInstanceOfSatisfying(Succeeded.class, succeeded -> {
            assertThat(succeeded.request().getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(succeeded.request().getCouponId()).isEqualTo(2001L);
            assertThat(succeeded.request().getReason()).isNull();
        });
        assertThat(waitingIds()).isEmpty();
        assertThat(processingIds()).isEmpty();
    }

    @DisplayName("성공으로 바꿀 때도 사유가 다르거나 이미 다시 대기했으면 손대지 않고 현재 작업을 돌려준다.")
    @Test
    void returnsChangedWhenMarkingSucceededAfterStateOrReasonChanged() {
        CouponIssueRequest otherReason = failedRequest(912L, "token-912", CouponIssueRequestReason.INTERNAL_ERROR);
        CouponIssueRequest requeued = failedRequest(913L, "token-913", CouponIssueRequestReason.DB_ERROR);
        queue.requeueFailed(requeued.getRequestId(), CouponIssueRequestReason.DB_ERROR, 20);

        assertThat(queue.markFailedSucceeded(otherReason.getRequestId(), CouponIssueRequestReason.DB_ERROR, 2002L))
            .isInstanceOfSatisfying(Changed.class, changed -> {
                assertThat(changed.request().getStatus()).isEqualTo(CouponIssueRequestStatus.FAILED);
                assertThat(changed.request().getReason()).isEqualTo(CouponIssueRequestReason.INTERNAL_ERROR);
                assertThat(changed.request().getCouponId()).isNull();
            });
        assertThat(queue.markFailedSucceeded(requeued.getRequestId(), CouponIssueRequestReason.DB_ERROR, 2003L))
            .isInstanceOfSatisfying(Changed.class, changed -> {
                assertThat(changed.request().getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
                assertThat(changed.request().getCouponId()).isNull();
            });
        assertThat(waitingIds()).containsExactly(requeued.getRequestId());
    }

    @DisplayName("업무 거절 사유를 기대하거나 작업 정보가 없으면 스크립트 오류로 거절한다.")
    @Test
    void rejectsBusinessReasonOrMissingJob() {
        CouponIssueRequest failed = failedRequest(910L, "token-910", CouponIssueRequestReason.SOLD_OUT);

        assertThatThrownBy(() -> queue.requeueFailed(failed.getRequestId(), CouponIssueRequestReason.SOLD_OUT, 20))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThatThrownBy(() -> queue.requeueFailed("missing-job-id", CouponIssueRequestReason.DB_ERROR, 20))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("실패한 작업이 대기열 List에 남아 있으면 저장 불일치로 멈추고 아무것도 바꾸지 않는다.")
    @Test
    void rejectsFailedRequestStillInQueueList() {
        CouponIssueRequest failed = failedRequest(911L, "token-911", CouponIssueRequestReason.DB_ERROR);
        redisTemplate.opsForList().rightPush(RedisCouponIssueQueue.PROCESSING_KEY, failed.getRequestId());

        assertThatThrownBy(() -> queue.requeueFailed(failed.getRequestId(), CouponIssueRequestReason.DB_ERROR, 20))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).isEmpty();
        assertThat(queue.findById(failed.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.FAILED));
    }

    private CouponIssueRequest failedRequest(Long userId, String token, CouponIssueRequestReason reason) {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, userId, REQUESTED_AT);
        queue.submit(request, 20);
        assertThat(queue.claim(token)).isInstanceOf(Claimed.class);
        queue.recordFailed(request.getRequestId(), token, reason);
        return request;
    }

    private List<CouponIssueQueueResubmission> requeueConcurrently(String requestId, int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(count);
        try {
            List<Future<CouponIssueQueueResubmission>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 재접수 barrier가 시작되지 않았습니다.");
                    }
                    return queue.requeueFailed(requestId, CouponIssueRequestReason.DB_ERROR, 20);
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CouponIssueQueueResubmission> results = new ArrayList<>();
            for (Future<CouponIssueQueueResubmission> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<String> waitingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.WAITING_KEY, 0, -1);
    }

    private List<String> processingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.PROCESSING_KEY, 0, -1);
    }
}
