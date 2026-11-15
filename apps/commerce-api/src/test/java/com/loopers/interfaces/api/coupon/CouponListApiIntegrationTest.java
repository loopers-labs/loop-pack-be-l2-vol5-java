package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.infrastructure.coupon.CouponJpaEntity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "coupon.queue.worker.enabled=false"
})
class CouponListApiIntegrationTest extends CouponApiTestSupport {
    @Test
    void 사용자는_본인에게_발급된_쿠폰만_조회한다() {
        long campaignId = activeCampaignWithNoIssues(2);
        long ownerId = user();
        long otherId = user();
        long ownerCouponId = coupons.saveAndFlush(new CouponJpaEntity(campaignId, ownerId, "할인권")).toDomain().couponId();
        coupons.saveAndFlush(new CouponJpaEntity(campaignId, otherId, "할인권"));

        var response = list(ownerId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data")).hasSize(1);
        assertThat(response.getBody().requiredAt("/data/0/couponId").asLong()).isEqualTo(ownerCouponId);
    }
}
