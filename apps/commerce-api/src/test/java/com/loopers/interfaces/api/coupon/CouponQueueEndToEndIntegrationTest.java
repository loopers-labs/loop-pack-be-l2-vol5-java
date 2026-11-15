package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.config.redis.RedisConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "coupon.queue.worker.enabled=true", "coupon.queue.worker.poll-delay-ms=20",
        "coupon.queue.open-on-start=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponQueueEndToEndIntegrationTest extends CouponApiTestSupport {
    private static final String KEY = "coupon:{queue-e2e-" + UUID.randomUUID() + "}";

    @DynamicPropertySource
    static void isolateQueue(DynamicPropertyRegistry registry) {
        registry.add("coupon.queue.key", () -> KEY);
    }

    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @Test
    void 두장_캠페인에_세명이_접수하면_작업자가_두장만_발급한다() {
        long campaign = activeCampaignWithNoIssues(2);
        long first = user();
        long second = user();
        long third = user();
        long fourth = user();

        var firstResponse = submit(campaign, first);
        var duplicateResponse = submit(campaign, first);
        var secondResponse = submit(campaign, second);
        var thirdResponse = submit(campaign, third);
        assertThat(firstResponse.getStatusCode().value()).isEqualTo(202);
        assertThat(secondResponse.getStatusCode().value()).isEqualTo(202);
        assertThat(thirdResponse.getStatusCode().value()).isEqualTo(202);
        var removedStatus = rest.exchange("/api/v1/coupon-campaigns/" + campaign + "/issue-requests/ticket-1",
                HttpMethod.GET, new HttpEntity<>(headers(first)), JsonNode.class);
        assertThat(removedStatus.getStatusCode().value()).isEqualTo(404);

        if (duplicateResponse.getStatusCode().value() == 202) {
            assertThat(duplicateResponse.getBody().at("/data/requestId").isMissingNode()).isTrue();
        } else {
            assertThat(duplicateResponse.getStatusCode().value()).isEqualTo(200);
            assertThat(duplicateResponse.getBody().requiredAt("/data/couponId").asLong())
                    .isEqualTo(coupons.findByCampaignIdAndUserId(campaign, first).orElseThrow().getId());
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(coupons.countByCampaignId(campaign)).isEqualTo(2);
            assertThat(redis.opsForHash().size(KEY + ":requests")).isZero();
            assertThat(redis.opsForHash().size(KEY + ":processing")).isZero();
        });
        assertThat(coupons.countByCampaignId(campaign)).isEqualTo(2);
        assertThat(campaigns.findById(campaign).orElseThrow().getIssuedCount()).isEqualTo(2);
        var afterSoldOut = submit(campaign, fourth);
        assertThat(afterSoldOut.getStatusCode().value()).isEqualTo(400);
        assertThat(afterSoldOut.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("COUPON_SOLD_OUT");
        // Set 대기열은 순서가 없으므로 실제 발급된 사용자 중 한 명을 선택한다.
        long issuedUser = List.of(first, second, third).stream()
                .filter(userId -> coupons.findByCampaignIdAndUserId(campaign, userId).isPresent())
                .findFirst().orElseThrow();
        var couponList = list(issuedUser);
        assertThat(couponList.getStatusCode().value()).isEqualTo(200);
        assertThat(couponList.getBody().requiredAt("/data/0/couponId").asLong())
                .isEqualTo(coupons.findByCampaignIdAndUserId(campaign, issuedUser).orElseThrow().getId());
        assertThat(redis.hasKey(KEY + ":completed")).isFalse();
        var alreadyIssued = submit(campaign, issuedUser);
        assertThat(alreadyIssued.getStatusCode().value()).isEqualTo(200);
        assertThat(alreadyIssued.getBody().requiredAt("/data/couponId").asLong())
                .isEqualTo(coupons.findByCampaignIdAndUserId(campaign, issuedUser).orElseThrow().getId());
    }

    @AfterEach
    void removeQueueOnly() {
        redis.delete(List.of(KEY + ":waiting", KEY + ":requests", KEY + ":users",
                KEY + ":processing", KEY + ":completed", KEY + ":campaigns"));
    }
}
