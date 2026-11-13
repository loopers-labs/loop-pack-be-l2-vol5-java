package com.loopers.application.coupon;

import com.loopers.application.coupon.dto.CouponWriteResult;
import com.loopers.application.coupon.dto.CouponWriteResult.Status;
import com.loopers.domain.common.Money;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 없이 DB 발급 TX 만으로 발급 총량·1인 1장·전체 rollback 을 지키는지 확인한다(과제의 "DB 발급 handler 직접 호출").
 * 각 호출은 실제 MySQL 을 지나는 독립 트랜잭션이다.
 */
@SpringBootTest
class CouponIssueWriterTest {

    private static final long DISCOUNT = 5_000;

    @Autowired
    private CouponIssueWriter couponIssueWriter;

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long campaignWithQuota(int totalQuota) {
        return couponCampaignRepository.save(CouponCampaign.create("5,000원 할인권", new Money(DISCOUNT), totalQuota)).getId();
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaigns where id = ?", Integer.class, campaignId);
    }

    private long couponRows(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupons where campaign_id = ?", Long.class, campaignId);
    }

    private long couponRowsOf(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from coupons where campaign_id = ? and user_id = ?", Long.class, campaignId, userId);
    }

    @DisplayName("당첨자의 쿠폰을 DB 에 저장할 때,")
    @Nested
    class Write {
        @DisplayName("쿠폰 한 장을 캠페인 혜택으로 저장하고 발급 수를 1 올린다.")
        @Test
        void write_savesCouponAndIncrementsIssuedCount() {
            // arrange
            Long campaignId = campaignWithQuota(5);

            // act
            CouponWriteResult result = couponIssueWriter.write(campaignId, 1L);

            // assert
            assertThat(result.status()).isEqualTo(Status.ISSUED);
            Long savedDiscount = jdbcTemplate.queryForObject(
                "select discount_amount from coupons where id = ?", Long.class, result.couponId());
            assertThat(savedDiscount).isEqualTo(DISCOUNT);
            assertThat(couponRows(campaignId)).isEqualTo(1);
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("발급 수가 이미 한도에 닿았으면, 먼저 넣은 쿠폰까지 rollback 하고 QUOTA_EXHAUSTED 를 돌려준다.")
        @Test
        void write_rollsBackCoupon_whenIssuedCountIsAlreadyFull() {
            // arrange
            Long campaignId = campaignWithQuota(1);
            couponIssueWriter.write(campaignId, 1L);

            // act
            CouponWriteResult result = couponIssueWriter.write(campaignId, 2L);

            // assert
            assertThat(result.status()).isEqualTo(Status.QUOTA_EXHAUSTED);
            assertThat(couponRowsOf(campaignId, 2L)).isZero();
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("같은 사용자가 이미 받았으면, 발급 수를 올리지 않고 기존 couponId 로 ALREADY_ISSUED 를 돌려준다.")
        @Test
        void write_returnsExistingCoupon_whenSameUserAlreadyHasOne() {
            // arrange
            Long campaignId = campaignWithQuota(5);
            CouponWriteResult first = couponIssueWriter.write(campaignId, 1L);

            // act
            CouponWriteResult second = couponIssueWriter.write(campaignId, 1L);

            // assert
            assertThat(second.status()).isEqualTo(Status.ALREADY_ISSUED);
            assertThat(second.couponId()).isEqualTo(first.couponId());
            assertThat(couponRows(campaignId)).isEqualTo(1);
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("한도 5장에 서로 다른 8명이 동시에 저장하면, 발급 5·한도 소진 3이고 쿠폰과 발급 수는 5다.")
        @Test
        void write_keepsIssuedCountWithinQuota_whenEightUsersCompeteForFive() {
            // arrange
            Long campaignId = campaignWithQuota(5);
            Queue<CouponWriteResult> writeResults = new ConcurrentLinkedQueue<>();
            List<Runnable> requests = new ArrayList<>();
            for (long userId = 1; userId <= 8; userId++) {
                long requester = userId;
                requests.add(() -> writeResults.add(couponIssueWriter.write(campaignId, requester)));
            }

            // act
            Results results = ConcurrentRequests.run(requests);

            // assert
            assertThat(results.technicalErrors()).isZero();
            assertThat(writeResults).filteredOn(r -> r.status() == Status.ISSUED).hasSize(5);
            assertThat(writeResults).filteredOn(r -> r.status() == Status.QUOTA_EXHAUSTED).hasSize(3);
            assertThat(couponRows(campaignId)).isEqualTo(5);
            assertThat(issuedCount(campaignId)).isEqualTo(5);
        }
    }
}
