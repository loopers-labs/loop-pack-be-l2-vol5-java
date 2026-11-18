package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.vo.CouponIssueRequested;
import com.loopers.support.CouponTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 접수 기록 묶음 적재를 실제 MySQL 의 유일 제약 · upsert 로 확인한다 (8주차 D-132, D-134, D-172) */
@SpringBootTest
class CouponIssueRequestLoaderIntegrationTest {

    @Autowired
    private CouponIssueRequestLoader loader;

    @Autowired
    private CouponTables couponTables;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        couponTables.truncate();
    }

    private Map<String, Object> rowOf(long campaignId, long userId) {
        return jdbc.queryForMap("SELECT sequence, status, slot_no FROM coupon_issue_request WHERE campaign_id = ? AND user_id = ?",
            campaignId, userId);
    }

    @DisplayName("한도 안은 WAITING(슬롯 = 순번), 밖은 RESERVE(슬롯 없음)로 적재한다. (D-121, D-141)")
    @Test
    void loadsWaitingAndReserve() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);

        // act
        List<CouponIssueRequested> mismatched = loader.load(List.of(
            new CouponIssueRequested(campaignId, 7, 1),
            new CouponIssueRequested(campaignId, 8, 3)));

        // assert
        assertAll(
            () -> assertThat(mismatched).isEmpty(),
            () -> assertThat(rowOf(campaignId, 7)).containsEntry("status", "WAITING").containsEntry("slot_no", 1),
            () -> assertThat(rowOf(campaignId, 8)).containsEntry("status", "RESERVE").containsEntry("slot_no", null)
        );
    }

    @DisplayName("같은 접수가 다시 와도(재전송) 한 행이고, 이미 바뀐 상태를 덮지 않는다. (D-132, D-134)")
    @Test
    void isIdempotent_andKeepsExistingState() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        loader.load(List.of(new CouponIssueRequested(campaignId, 7, 1)));
        jdbc.update("UPDATE coupon_issue_request SET status = 'ISSUED', coupon_id = 900 WHERE user_id = 7");

        // act
        List<CouponIssueRequested> mismatched = loader.load(List.of(
            new CouponIssueRequested(campaignId, 7, 1),
            new CouponIssueRequested(campaignId, 7, 1)));

        // assert
        assertAll(
            () -> assertThat(mismatched).isEmpty(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issue_request", Long.class)).isEqualTo(1L),
            () -> assertThat(rowOf(campaignId, 7)).containsEntry("status", "ISSUED")
        );
    }

    @DisplayName("저장된 순번과 다르거나 같은 순번이 다른 사람에게 이미 있으면, 어긋난 접수로 돌려준다.")
    @Test
    void reportsMismatch() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        loader.load(List.of(new CouponIssueRequested(campaignId, 7, 1)));

        // act
        List<CouponIssueRequested> mismatched = loader.load(List.of(
            new CouponIssueRequested(campaignId, 7, 2),
            new CouponIssueRequested(campaignId, 8, 1)));

        // assert
        assertThat(mismatched).containsExactlyInAnyOrder(
            new CouponIssueRequested(campaignId, 7, 2),
            new CouponIssueRequested(campaignId, 8, 1));
    }

    @DisplayName("캠페인이 없으면 적재하지 않고 어긋난 접수로 돌려준다.")
    @Test
    void reportsMissingCampaign() {
        List<CouponIssueRequested> mismatched = loader.load(List.of(new CouponIssueRequested(999, 7, 1)));

        assertAll(
            () -> assertThat(mismatched).hasSize(1),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issue_request", Long.class)).isZero()
        );
    }
}
