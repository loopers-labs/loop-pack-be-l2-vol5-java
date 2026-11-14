package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueClaim;
import com.loopers.application.coupon.CouponIssueQueueClaim.Claimed;
import com.loopers.application.coupon.CouponIssueQueueClaim.Empty;
import com.loopers.application.coupon.CouponIssueQueueScriptException;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RedisCouponIssueQueueClaimIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 9002L;
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

    @DisplayName("대기 작업이 없으면 가져올 작업이 없다고 돌려주고 아무것도 바꾸지 않는다.")
    @Test
    void returnsEmptyWhenNothingIsWaiting() {
        assertThat(queue.hasWaiting()).isFalse();

        assertThat(queue.claim("token-empty")).isInstanceOf(Empty.class);
        assertThat(processingIds()).isEmpty();
    }

    @DisplayName("대기 List 맨 앞의 작업을 가져와 처리 중으로 바꾸고 실행 token을 기록한다.")
    @Test
    void claimsHeadOfWaitingListAndRecordsToken() {
        CouponIssueRequest first = submit(701L);
        CouponIssueRequest second = submit(702L);
        assertThat(queue.hasWaiting()).isTrue();

        CouponIssueQueueClaim claim = queue.claim("token-first");

        assertThat(claim).isInstanceOfSatisfying(Claimed.class, claimed -> {
            assertThat(claimed.request().getRequestId()).isEqualTo(first.getRequestId());
            assertThat(claimed.request().getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING);
            assertThat(claimed.request().getExecutionToken()).isEqualTo("token-first");
        });
        assertThat(waitingIds()).containsExactly(second.getRequestId());
        assertThat(processingIds()).containsExactly(first.getRequestId());
        assertThat(queue.findById(first.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING);
            assertThat(stored.getExecutionToken()).isEqualTo("token-first");
            assertThat(stored.getRequestedAt()).isEqualTo(REQUESTED_AT);
        });
    }

    @DisplayName("여러 worker가 동시에 가져가도 같은 작업을 두 번 가져가지 않는다.")
    @Test
    void neverClaimsSameRequestTwice() throws Exception {
        for (long userId = 710L; userId < 715L; userId++) {
            submit(userId);
        }

        List<CouponIssueQueueClaim> claims = claimConcurrently(10);

        List<String> claimedIds = claims.stream().filter(Claimed.class::isInstance)
            .map(claim -> ((Claimed) claim).request().getRequestId()).toList();
        assertThat(claimedIds).hasSize(5).doesNotHaveDuplicates();
        assertThat(claims).filteredOn(Empty.class::isInstance).hasSize(5);
        assertThat(processingIds()).hasSize(5).doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(claimedIds);
        assertThat(waitingIds()).isEmpty();
    }

    @DisplayName("대기 항목에 작업 정보가 없으면 저장 불일치로 멈추고 두 List를 바꾸지 않는다.")
    @Test
    void rejectsWaitingEntryWithoutJob() {
        redisTemplate.opsForList().rightPush(RedisCouponIssueQueue.WAITING_KEY, "missing-job-id");

        assertThatThrownBy(() -> queue.claim("token-missing")).isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).containsExactly("missing-job-id");
        assertThat(processingIds()).isEmpty();
    }

    @DisplayName("대기 항목의 작업이 대기 상태가 아니면 저장 불일치로 멈추고 두 List를 바꾸지 않는다.")
    @Test
    void rejectsWaitingEntryWhoseJobIsNotWaiting() {
        CouponIssueRequest request = submit(720L);
        queue.claim("token-taken");
        redisTemplate.opsForList().leftPush(RedisCouponIssueQueue.WAITING_KEY, request.getRequestId());

        assertThatThrownBy(() -> queue.claim("token-again")).isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).containsExactly(request.getRequestId());
        assertThat(processingIds()).containsExactly(request.getRequestId());
        assertThat(queue.findById(request.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getExecutionToken()).isEqualTo("token-taken"));
    }

    @DisplayName("실행 token이 비어 있으면 스크립트 오류로 거절하고 작업을 가져가지 않는다.")
    @Test
    void rejectsBlankToken() {
        CouponIssueRequest request = submit(730L);

        assertThatThrownBy(() -> queue.claim("")).isInstanceOf(CouponIssueQueueScriptException.class);
        assertThatThrownBy(() -> queue.claim(null)).isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(waitingIds()).containsExactly(request.getRequestId());
        assertThat(processingIds()).isEmpty();
    }

    private CouponIssueRequest submit(Long userId) {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, userId, REQUESTED_AT);
        queue.submit(request, 20);
        return request;
    }

    private List<CouponIssueQueueClaim> claimConcurrently(int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(count);
        try {
            List<Future<CouponIssueQueueClaim>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String token = UUID.randomUUID().toString();
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 가져오기 barrier가 시작되지 않았습니다.");
                    }
                    return queue.claim(token);
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CouponIssueQueueClaim> claims = new ArrayList<>();
            for (Future<CouponIssueQueueClaim> future : futures) {
                claims.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return claims;
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
