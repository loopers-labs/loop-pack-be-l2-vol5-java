package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponIssueWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final CouponFacade couponFacade;
    private final CouponIssueWorker worker;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssueWorkerTest(CouponFacade couponFacade, CouponIssueWorker worker, CampaignCache campaignCache,
                          JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.worker = worker;
        this.campaignCache = campaignCache;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    private Long openCampaign(int quota) {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(quota), true).getId();
    }

    private int couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ?", Integer.class, campaignId);
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaign where id = ?", Integer.class, campaignId);
    }

    private List<Integer> issueNumbers(Long campaignId) {
        return jdbcTemplate.queryForList("select issue_number from coupon where campaign_id = ? order by issue_number",
            Integer.class, campaignId);
    }

    @DisplayName("WRK-011: 묶음의 접수들은 번호 그대로 쿠폰이 되고 발급 수는 묶음 크기만큼 오른다")
    @Test
    void recordsBatchAsCoupons() {
        Long campaignId = openCampaign(100);

        worker.record(List.of(new CouponIssue(campaignId, 42L, 1, NOW), new CouponIssue(campaignId, 7L, 2, NOW),
            new CouponIssue(campaignId, 9L, 3, NOW)));

        assertThat(issueNumbers(campaignId)).containsExactly(1, 2, 3);
        assertThat(issuedCount(campaignId)).isEqualTo(3);
    }

    @DisplayName("WRK-011: 묶음 안에 같은 접수가 두 번 있으면 건별로 다시 풀려 나머지는 기록되고 쿠폰은 둘이다")
    @Test
    void recordsRestOfBatchWhenItContainsDuplicate() {
        Long campaignId = openCampaign(100);

        worker.record(List.of(new CouponIssue(campaignId, 42L, 1, NOW), new CouponIssue(campaignId, 42L, 1, NOW),
            new CouponIssue(campaignId, 7L, 2, NOW)));

        assertThat(issueNumbers(campaignId)).containsExactly(1, 2);
        assertThat(issuedCount(campaignId)).isEqualTo(2);
    }

    @DisplayName("WRK-011: 묶음이 한도를 넘으면 건별로 다시 풀려 한도까지만 기록되고 넘친 접수는 쿠폰 없이 넘어간다")
    @Test
    void recordsUpToQuotaWhenBatchExceedsIt() {
        Long campaignId = openCampaign(2);

        worker.record(List.of(new CouponIssue(campaignId, 42L, 1, NOW), new CouponIssue(campaignId, 7L, 2, NOW),
            new CouponIssue(campaignId, 9L, 3, NOW)));

        assertThat(issueNumbers(campaignId)).containsExactly(1, 2);
        assertThat(issuedCount(campaignId)).isEqualTo(2);
    }

    @DisplayName("WRK-011: 묶음 가운데 없는 캠페인의 접수가 있으면 앞은 기록되고 그 위치에서 멈추며 뒤는 기록되지 않는다")
    @Test
    void stopsAtTheRejectedRecordAndReportsItsIndex() {
        Long campaignId = openCampaign(100);
        long unknownCampaignId = 999_999L;

        assertThatThrownBy(() -> worker.record(List.of(new CouponIssue(campaignId, 42L, 1, NOW),
            new CouponIssue(unknownCampaignId, 7L, 1, NOW), new CouponIssue(campaignId, 9L, 2, NOW))))
            .isInstanceOf(CouponIssueWorker.RecordFailed.class)
            .hasFieldOrPropertyWithValue("index", 1)
            .cause().isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_CAMPAIGN_NOT_FOUND);

        assertThat(issueNumbers(campaignId)).containsExactly(1);
        assertThat(couponCount(campaignId)).isEqualTo(1);
    }
}
