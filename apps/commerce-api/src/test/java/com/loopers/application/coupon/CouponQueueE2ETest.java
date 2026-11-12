package com.loopers.application.coupon;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponQueuePort;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponIssuanceJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponQueueE2ETest {

    @Autowired CouponFacade couponFacade;
    @Autowired CouponAdmissionGate admissionGate;
    @Autowired CouponQueuePort queuePort;
    @Autowired CouponCampaignJpaRepository campaignJpaRepository;
    @Autowired CouponIssuanceJpaRepository issuanceJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @Autowired RedisCleanUp redisCleanUp;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        CouponCampaignModel campaign = campaignJpaRepository.save(
            new CouponCampaignModel("테스트 쿠폰", 10, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
        );
        campaignId = campaign.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        admissionGate.close(campaignId != null ? campaignId : 0L);
    }

    @DisplayName("게이트가 열려 있을 때 대기열에 진입할 수 있다.")
    @Test
    void enqueue_gateOpen_success() {
        admissionGate.open(campaignId);
        CouponInfo.QueueEntry entry = couponFacade.enqueue(campaignId, 1L);
        assertThat(entry.campaignId()).isEqualTo(campaignId);
        assertThat(entry.position()).isPositive();
    }

    @DisplayName("게이트가 닫혀 있으면 대기열 진입이 차단된다.")
    @Test
    void enqueue_gateClosed_throws503() {
        assertThatThrownBy(() -> couponFacade.enqueue(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
    }

    @DisplayName("대기열이 가득 차면 429를 반환한다.")
    @Test
    void enqueue_queueFull_throws429() {
        admissionGate.open(campaignId);
        for (long i = 1; i <= 10; i++) {
            queuePort.enqueue(campaignId, i, 10);
        }
        assertThatThrownBy(() -> couponFacade.enqueue(campaignId, 99L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.TOO_MANY_REQUESTS));
    }

    @DisplayName("대기열 진입 후 승격, 발급까지 전체 흐름이 동작한다.")
    @Test
    void fullFlow_enqueuePromoteIssue() {
        admissionGate.open(campaignId);
        couponFacade.enqueue(campaignId, 1L);

        queuePort.promote(campaignId, 5, 30L, 30L);

        CouponInfo.IssuedCoupon issuance = couponFacade.issueCoupon(campaignId, 1L);
        assertThat(issuance.campaignId()).isEqualTo(campaignId);
        assertThat(issuance.userId()).isEqualTo(1L);
    }

    @DisplayName("쿠폰 발급은 멱등하다 - 중복 요청 시 기존 발급 결과를 반환한다.")
    @Test
    void issueCoupon_idempotent() {
        admissionGate.open(campaignId);
        couponFacade.enqueue(campaignId, 1L);
        queuePort.promote(campaignId, 5, 30L, 30L);
        couponFacade.issueCoupon(campaignId, 1L);

        couponFacade.enqueue(campaignId, 1L);
        queuePort.promote(campaignId, 5, 30L, 30L);
        queuePort.start(campaignId, 1L, 30L);

        long count = issuanceJpaRepository.findAll().stream()
            .filter(i -> i.getCampaignId().equals(campaignId) && i.getUserId().equals(1L))
            .count();
        assertThat(count).isEqualTo(1);
    }

    @DisplayName("admitted 상태가 아닌 사용자는 발급을 받을 수 없다.")
    @Test
    void issueCoupon_notAdmitted_throwsBadRequest() {
        admissionGate.open(campaignId);
        assertThatThrownBy(() -> couponFacade.issueCoupon(campaignId, 1L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.BAD_REQUEST));
    }

    @DisplayName("발급 후 active 상태에서 release되어 다음 사용자가 진입할 수 있다.")
    @Test
    void issueCoupon_releasesActiveSlot() {
        admissionGate.open(campaignId);
        couponFacade.enqueue(campaignId, 1L);
        couponFacade.enqueue(campaignId, 2L);
        queuePort.promote(campaignId, 1, 30L, 30L);

        couponFacade.issueCoupon(campaignId, 1L);

        int promoted = queuePort.promote(campaignId, 1, 30L, 30L);
        assertThat(promoted).isEqualTo(1);
    }

    @DisplayName("어드민이 게이트를 열고 닫을 수 있다.")
    @Test
    void admin_openAndCloseGate() {
        couponFacade.openGate(campaignId);
        assertThat(admissionGate.isOpen(campaignId)).isTrue();

        couponFacade.closeGate(campaignId);
        assertThat(admissionGate.isOpen(campaignId)).isFalse();
    }

    @DisplayName("어드민이 openGate 호출 시 존재하지 않는 캠페인이면 404를 반환한다.")
    @Test
    void admin_openGate_notFound() {
        assertThatThrownBy(() -> couponFacade.openGate(999L))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.NOT_FOUND));
    }
}
