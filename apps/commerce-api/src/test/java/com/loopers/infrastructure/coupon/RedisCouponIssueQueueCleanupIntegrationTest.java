package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueCleanup;
import com.loopers.application.coupon.CouponIssueQueueCleanup.Blocked;
import com.loopers.application.coupon.CouponIssueQueueCleanup.Cleared;
import com.loopers.domain.coupon.CouponIssueRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisCouponIssueQueueCleanupIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 9005L;
    private static final Long OTHER_CAMPAIGN_ID = 9006L;

    @Autowired private CouponIssueQueue queue;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUpKeys() {
        redisTemplate.delete(List.of(RedisCouponIssuePermits.PERMITS_KEY, RedisCouponIssueQueue.WAITING_KEY,
            RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }

    @DisplayName("남은 실행이 없으면 해당 캠페인의 작업 정보와 중복 접수 정보만 지우고 다른 캠페인은 남긴다.")
    @Test
    void clearsOnlyTargetCampaign() {
        CouponIssueRequest target = completed(CAMPAIGN_ID, 1001L, "token-1001");
        CouponIssueRequest other = completed(OTHER_CAMPAIGN_ID, 1002L, "token-1002");
        CouponIssueRequest otherWaiting = waiting(OTHER_CAMPAIGN_ID, 1003L);

        CouponIssueQueueCleanup result = queue.clearCampaign(CAMPAIGN_ID);

        assertThat(result).isEqualTo(new Cleared(1));
        assertThat(queue.findById(target.getRequestId())).isEmpty();
        assertThat(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, 1001L)).isEmpty();
        assertThat(queue.findById(other.getRequestId())).isPresent();
        assertThat(queue.findByCampaignIdAndUserId(OTHER_CAMPAIGN_ID, 1002L)).isPresent();
        assertThat(waitingIds()).containsExactly(otherWaiting.getRequestId());
    }

    @DisplayName("해당 캠페인의 작업이 대기 중이면 아무것도 지우지 않는다.")
    @Test
    void blocksWhenTargetCampaignHasWaitingRequest() {
        CouponIssueRequest done = completed(CAMPAIGN_ID, 1011L, "token-1011");
        CouponIssueRequest pending = waiting(CAMPAIGN_ID, 1012L);

        assertThat(queue.clearCampaign(CAMPAIGN_ID)).isInstanceOf(Blocked.class);
        assertThat(queue.findById(done.getRequestId())).isPresent();
        assertThat(queue.findById(pending.getRequestId())).isPresent();
        assertThat(waitingIds()).containsExactly(pending.getRequestId());
    }

    @DisplayName("해당 캠페인의 작업이 처리 중이면 아무것도 지우지 않는다.")
    @Test
    void blocksWhenTargetCampaignHasProcessingRequest() {
        CouponIssueRequest pending = waiting(CAMPAIGN_ID, 1021L);
        queue.claim("token-1021");

        assertThat(queue.clearCampaign(CAMPAIGN_ID)).isInstanceOf(Blocked.class);
        assertThat(queue.findById(pending.getRequestId())).isPresent();
    }

    @DisplayName("permit token이 남아 있으면 캠페인과 관계없이 아무것도 지우지 않는다.")
    @Test
    void blocksWhenPermitTokenRemains() {
        CouponIssueRequest done = completed(CAMPAIGN_ID, 1031L, "token-1031");
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, "token-of-another-campaign");

        assertThat(queue.clearCampaign(CAMPAIGN_ID)).isInstanceOf(Blocked.class);
        assertThat(queue.findById(done.getRequestId())).isPresent();
    }

    private CouponIssueRequest waiting(Long campaignId, Long userId) {
        CouponIssueRequest request = CouponIssueRequest.create(campaignId, userId, REQUESTED_AT);
        queue.submit(request, 20);
        return request;
    }

    /**
     * 대기 List 맨 앞에 있는 작업을 가져가 성공으로 끝낸다. 대기 List가 비어 있을 때 호출한다.
     */
    private CouponIssueRequest completed(Long campaignId, Long userId, String token) {
        CouponIssueRequest request = waiting(campaignId, userId);
        queue.claim(token);
        queue.recordSucceeded(request.getRequestId(), token, userId);
        return request;
    }

    private List<String> waitingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.WAITING_KEY, 0, -1);
    }
}
