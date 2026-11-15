package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class CouponPersistenceTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Test
    void storesCampaignCountAndCouponSnapshot() {
        CouponCampaignModel campaign = campaign();
        // 이 테스트는 저장 매핑만 확인한다. 조건부 UPDATE·동일 TX의 원자성은 발급 통합 테스트에서 확인한다.
        entityManager.flush();
        entityManager.createNativeQuery("update coupon_campaign set issued_count = 1 where id = ?")
            .setParameter(1, campaign.getId()).executeUpdate();
        CouponModel coupon = coupon(1L, campaign);
        entityManager.flush();
        entityManager.clear();

        CouponCampaignModel storedCampaign = entityManager.find(CouponCampaignModel.class, campaign.getId());
        CouponModel storedCoupon = entityManager.find(CouponModel.class, coupon.getId());

        assertThat(storedCampaign.getName()).isEqualTo("행사");
        assertThat(storedCampaign.isActive()).isTrue();
        assertThat(storedCampaign.getBenefitDescription()).isEqualTo("5,000원 할인권");
        assertThat(storedCampaign.getTotalQuota()).isEqualTo(5);
        assertThat(storedCampaign.getIssuedCount()).isEqualTo(1);
        assertThat(storedCoupon.getId()).isPositive();
        assertThat(storedCoupon.getUserId()).isEqualTo(1L);
        assertThat(storedCoupon.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(storedCoupon.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(storedCoupon.getBenefitDescription()).isEqualTo("5,000원 할인권");
    }

    @Test
    void databaseRejectsSecondCouponForSameCampaignAndUser() {
        CouponCampaignModel campaign = campaign();
        coupon(1L, campaign);
        entityManager.flush();

        assertThatThrownBy(() -> {
            coupon(1L, campaign);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
            .hasMessageContaining("uk_coupon_campaign_user");
    }

    @Test
    void allowsDifferentUsersInOneCampaignAndOneUserInDifferentCampaigns() {
        CouponCampaignModel first = campaign();
        CouponCampaignModel second = campaign();
        CouponModel firstCoupon = coupon(1L, first);
        CouponModel otherUserCoupon = coupon(2L, first);
        CouponModel otherCampaignCoupon = coupon(1L, second);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(CouponModel.class, firstCoupon.getId())).isNotNull();
        assertThat(entityManager.find(CouponModel.class, otherUserCoupon.getId())).isNotNull();
        assertThat(entityManager.find(CouponModel.class, otherCampaignCoupon.getId())).isNotNull();
    }

    private CouponCampaignModel campaign() {
        CouponCampaignModel campaign = new CouponCampaignModel("행사", "5,000원 할인권", true, 5, ISSUED_AT.plusSeconds(3600));
        entityManager.persist(campaign);
        return campaign;
    }

    private CouponModel coupon(Long userId, CouponCampaignModel campaign) {
        CouponModel coupon = new CouponModel(userId, campaign.getId(), ISSUED_AT, campaign.getBenefitDescription());
        entityManager.persist(coupon);
        return coupon;
    }
}
