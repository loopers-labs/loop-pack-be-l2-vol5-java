package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.entity.Coupon;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.entity.CouponIssueRequest;
import com.loopers.domain.coupon.enums.CampaignStatus;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class CouponPersistenceIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-13T10:00:00+09:00");

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private CouponIssueRequestJpaRepository couponIssueRequestJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private CouponCampaign openCampaign(int totalQuota) {
        CouponCampaign campaign = new CouponCampaign("행사", totalQuota, DiscountType.FIXED_AMOUNT, 5_000L, "5,000원 할인권");
        campaign.open();
        return couponCampaignRepository.save(campaign);
    }

    @DisplayName("캠페인을 저장하고 읽으면, 상태 · 한도 · 혜택이 그대로다.")
    @Test
    void savesAndReadsCampaign() {
        // arrange
        CouponCampaign saved = openCampaign(5);

        // act
        CouponCampaign found = couponCampaignRepository.findById(saved.getId()).orElseThrow();

        // assert
        assertAll(
            () -> assertThat(found.getStatus()).isEqualTo(CampaignStatus.OPEN),
            () -> assertThat(found.getTotalQuota()).isEqualTo(5),
            () -> assertThat(found.getDiscountType()).isEqualTo(DiscountType.FIXED_AMOUNT),
            () -> assertThat(found.getDescription()).isEqualTo("5,000원 할인권")
        );
    }

    @DisplayName("같은 캠페인에서 같은 사용자의 쿠폰을 두 장 저장하면, 유일 제약이 막는다. (D-115)")
    @Test
    void rejectsSecondCouponOfSameUser() {
        // arrange
        CouponCampaign campaign = openCampaign(5);
        couponJpaRepository.save(Coupon.issue(campaign, 7L, 1, NOW));

        // act & assert
        assertThatThrownBy(() -> couponJpaRepository.save(Coupon.issue(campaign, 7L, 2, NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("같은 슬롯에 두 사용자의 쿠폰을 저장하면, 유일 제약이 막는다. (D-141)")
    @Test
    void rejectsSecondCouponOnSameSlot() {
        // arrange
        CouponCampaign campaign = openCampaign(5);
        couponJpaRepository.save(Coupon.issue(campaign, 7L, 1, NOW));

        // act & assert
        assertThatThrownBy(() -> couponJpaRepository.save(Coupon.issue(campaign, 8L, 1, NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("한도 5 에 서로 다른 8명이 겹치는 슬롯으로 동시에 발급하면, 5장만 남는다. (D-141)")
    @Test
    void keepsAtMostQuota_whenEightUsersRaceForFiveSlots() throws Exception {
        // arrange
        CouponCampaign campaign = openCampaign(5);
        int users = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(users);
        List<Future<Boolean>> results = new ArrayList<>();

        // act
        try {
            for (int i = 0; i < users; i++) {
                long userId = 100L + i;
                int slotNo = i % 5 + 1;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        couponJpaRepository.save(Coupon.issue(campaign, userId, slotNo, NOW));
                        return true;
                    } catch (DataIntegrityViolationException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // assert
        long issued = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                issued++;
            }
        }
        long saved = issued;
        assertAll(
            () -> assertThat(saved).isEqualTo(5),
            () -> assertThat(couponJpaRepository.count()).isEqualTo(5)
        );
    }

    @DisplayName("같은 캠페인에서 같은 사용자의 접수 기록을 두 번 저장하면, 유일 제약이 막는다. (D-134 멱등 적재)")
    @Test
    void rejectsSecondRequestOfSameUser() {
        // arrange
        couponIssueRequestJpaRepository.save(CouponIssueRequest.accepted(1L, 7L, 1, 5));

        // act & assert
        assertThatThrownBy(() -> couponIssueRequestJpaRepository.save(CouponIssueRequest.accepted(1L, 7L, 2, 5)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("같은 캠페인에서 같은 순번의 접수 기록을 두 번 저장하면, 유일 제약이 막는다. (D-115)")
    @Test
    void rejectsSecondRequestWithSameSequence() {
        // arrange
        couponIssueRequestJpaRepository.save(CouponIssueRequest.accepted(1L, 7L, 1, 5));

        // act & assert
        assertThatThrownBy(() -> couponIssueRequestJpaRepository.save(CouponIssueRequest.accepted(1L, 8L, 1, 5)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
