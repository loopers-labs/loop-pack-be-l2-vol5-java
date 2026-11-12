package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.application.coupon.CouponIssueQueueSubmission;
import com.loopers.application.coupon.CouponIssueQueueSubmission.Created;
import com.loopers.application.coupon.CouponIssueQueueSubmission.Existing;
import com.loopers.application.coupon.CouponIssueQueueSubmission.Full;
import com.loopers.domain.coupon.CouponIssueRequest;
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
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RedisCouponIssueQueueIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00.123456Z");
    private static final Long CAMPAIGN_ID = 9001L;
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

    @DisplayName("새 작업을 접수하면 작업 정보, 중복 접수 정보, 대기 List에 저장하고 두 방법으로 같은 작업을 조회한다.")
    @Test
    void storesNewRequestAndFindsItBothWays() {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, 501L, REQUESTED_AT);

        CouponIssueQueueSubmission result = queue.submit(request, 20);

        assertThat(result).isInstanceOfSatisfying(Created.class,
            created -> assertSameRequest(created.request(), request));
        assertThat(waitingIds()).containsExactly(request.getRequestId());
        assertThat(redisTemplate.opsForHash().get(RedisCouponIssueQueue.REQUESTS_KEY, CAMPAIGN_ID + ":501"))
            .isEqualTo(request.getRequestId());
        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(found -> assertSameRequest(found, request));
        assertThat(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, 501L))
            .hasValueSatisfying(found -> assertSameRequest(found, request));
    }

    @DisplayName("없는 작업 ID나 접수하지 않은 사용자는 빈 결과로 조회한다.")
    @Test
    void findsNothingForUnknownRequest() {
        assertThat(queue.findById("unknown-request-id")).isEmpty();
        assertThat(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, 502L)).isEmpty();
    }

    @DisplayName("같은 사용자와 캠페인이 다시 접수하면 기존 작업을 돌려주고 새 대기 항목을 만들지 않는다.")
    @Test
    void returnsExistingRequestForSameUserAndCampaign() {
        CouponIssueRequest first = CouponIssueRequest.create(CAMPAIGN_ID, 503L, REQUESTED_AT);
        queue.submit(first, 20);

        CouponIssueQueueSubmission again = queue.submit(
            CouponIssueRequest.create(CAMPAIGN_ID, 503L, REQUESTED_AT.plusSeconds(1)), 20);

        assertThat(again).isInstanceOfSatisfying(Existing.class,
            existing -> assertSameRequest(existing.request(), first));
        assertThat(waitingIds()).containsExactly(first.getRequestId());
        assertThat(redisTemplate.opsForHash().size(RedisCouponIssueQueue.JOBS_KEY)).isEqualTo(1L);
    }

    @DisplayName("같은 사용자가 동시에 접수해도 작업과 대기 항목은 하나만 생긴다.")
    @Test
    void createsOneRequestForConcurrentDuplicateSubmissions() throws Exception {
        List<CouponIssueQueueSubmission> results = submitConcurrently(10,
            index -> CouponIssueRequest.create(CAMPAIGN_ID, 504L, REQUESTED_AT), 20);

        assertThat(results).filteredOn(Created.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(Existing.class::isInstance).hasSize(9);
        assertThat(waitingIds()).hasSize(1);
        assertThat(redisTemplate.opsForHash().size(RedisCouponIssueQueue.JOBS_KEY)).isEqualTo(1L);
    }

    @DisplayName("서로 다른 사용자가 동시에 마지막 자리를 요청해도 미완료 한도를 넘지 않는다.")
    @Test
    void respectsCapacityUnderConcurrentSubmissions() throws Exception {
        List<CouponIssueQueueSubmission> results = submitConcurrently(10,
            index -> CouponIssueRequest.create(CAMPAIGN_ID, 600L + index, REQUESTED_AT), 3);

        assertThat(results).filteredOn(Created.class::isInstance).hasSize(3);
        assertThat(results).filteredOn(Full.class::isInstance).hasSize(7);
        assertThat(waitingIds()).hasSize(3);
    }

    @DisplayName("처리 중 List의 작업도 미완료 한도에 포함한다.")
    @Test
    void countsProcessingRequestsTowardCapacity() {
        redisTemplate.opsForList().rightPush(RedisCouponIssueQueue.PROCESSING_KEY, "processing-request-id");

        CouponIssueQueueSubmission result = queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 505L, REQUESTED_AT), 1);

        assertThat(result).isInstanceOf(Full.class);
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("한도가 찼어도 같은 사용자와 캠페인의 재접수는 기존 작업을 돌려준다.")
    @Test
    void returnsExistingRequestEvenWhenFull() {
        CouponIssueRequest first = CouponIssueRequest.create(CAMPAIGN_ID, 506L, REQUESTED_AT);
        queue.submit(first, 1);

        assertThat(queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 507L, REQUESTED_AT), 1)).isInstanceOf(Full.class);
        assertThat(queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 506L, REQUESTED_AT), 1))
            .isInstanceOfSatisfying(Existing.class, existing -> assertSameRequest(existing.request(), first));
    }

    @DisplayName("한도 입력이 올바르지 않으면 스크립트 오류로 거절하고 아무것도 저장하지 않는다.")
    @Test
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 508L, REQUESTED_AT), 0))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("대기열 키의 타입이 다르면 스크립트 오류로 거절한다.")
    @Test
    void rejectsWrongKeyType() {
        redisTemplate.opsForValue().set(RedisCouponIssueQueue.REQUESTS_KEY, "not-a-hash");

        assertThatThrownBy(() -> queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 509L, REQUESTED_AT), 20))
            .isInstanceOf(CouponIssueQueueScriptException.class);
    }

    @DisplayName("중복 접수 정보는 있는데 작업 정보가 없으면 저장 불일치로 거절한다.")
    @Test
    void rejectsRequestIndexWithoutJob() {
        redisTemplate.opsForHash().put(RedisCouponIssueQueue.REQUESTS_KEY, CAMPAIGN_ID + ":510", "missing-job-id");

        assertThatThrownBy(() -> queue.submit(CouponIssueRequest.create(CAMPAIGN_ID, 510L, REQUESTED_AT), 20))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("저장된 작업 정보가 상태와 맞지 않으면 읽을 때 저장 불일치로 거절한다.")
    @Test
    void rejectsInconsistentStoredJobOnRead() {
        redisTemplate.opsForHash().put(RedisCouponIssueQueue.JOBS_KEY, "broken-job-id",
            "{\"requestId\":\"broken-job-id\",\"campaignId\":\"9001\",\"userId\":\"511\",\"status\":\"PROCESSING\","
                + "\"requestedAt\":\"2026-11-13T00:00:00Z\",\"couponId\":null,\"reasonCode\":null,\"executionToken\":null}");

        assertThatThrownBy(() -> queue.findById("broken-job-id")).isInstanceOf(CouponIssueQueueScriptException.class);
    }

    private List<CouponIssueQueueSubmission> submitConcurrently(int count, IntFunction<CouponIssueRequest> requestOf,
                                                                int capacity) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(count);
        try {
            List<Future<CouponIssueQueueSubmission>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                CouponIssueRequest request = requestOf.apply(index);
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 접수 barrier가 시작되지 않았습니다.");
                    }
                    return queue.submit(request, capacity);
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CouponIssueQueueSubmission> results = new ArrayList<>();
            for (Future<CouponIssueQueueSubmission> future : futures) {
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

    private static void assertSameRequest(CouponIssueRequest actual, CouponIssueRequest expected) {
        assertThat(actual.getRequestId()).isEqualTo(expected.getRequestId());
        assertThat(actual.getCampaignId()).isEqualTo(expected.getCampaignId());
        assertThat(actual.getUserId()).isEqualTo(expected.getUserId());
        assertThat(actual.getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
        assertThat(actual.getRequestedAt()).isEqualTo(expected.getRequestedAt());
        assertThat(actual.getCouponId()).isNull();
        assertThat(actual.getReason()).isNull();
        assertThat(actual.getExecutionToken()).isNull();
    }
}
