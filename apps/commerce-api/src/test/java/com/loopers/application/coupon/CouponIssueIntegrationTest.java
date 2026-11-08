package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponIssueIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final long TIMEOUT_SECONDS = 10;

    private final CouponFacade couponFacade;
    private final CouponTransactions couponTransactions;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    CouponIssueIntegrationTest(CouponFacade couponFacade, CouponTransactions couponTransactions,
                               JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.couponFacade = couponFacade;
        this.couponTransactions = couponTransactions;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long openCampaign(int quota, boolean active) {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(quota), active).getId();
    }

    private int couponRows(Long campaignId) {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Integer.class,
            campaignId);
        return rows == null ? -1 : rows;
    }

    private int issuedCount(Long campaignId) {
        Integer count = jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?",
            Integer.class, campaignId);
        return count == null ? -1 : count;
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String issueInDb(Long campaignId, Long userId) {
        try {
            couponTransactions.issue(campaignId, userId, NOW);
            return "ISSUED";
        } catch (DomainException e) {
            return e.error().name();
        }
    }

    @DisplayName("EXP-019: 한도 5 에 8 명이 동시에 DB 발급에 들어가면 5 명이 받고 3 명은 품절이다")
    @Test
    void quotaHoldsUnderRace() throws Exception {
        Long campaignId = openCampaign(5, true);
        List<Callable<String>> tasks = new ArrayList<>();
        for (long user = 1; user <= 8; user++) {
            long userId = user;
            tasks.add(() -> issueInDb(campaignId, userId));
        }

        List<String> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn("ISSUED"::equals).hasSize(5);
        assertThat(outcomes).filteredOn(DomainError.COUPON_SOLD_OUT.name()::equals).hasSize(3);
        assertThat(couponRows(campaignId)).isEqualTo(5);
        assertThat(issuedCount(campaignId)).isEqualTo(5);
    }

    @DisplayName("ISS-006: 같은 사용자가 동시에 두 번 발급하면 쿠폰은 하나다")
    @Test
    void sameUserRaceIssuesOnce() throws Exception {
        Long campaignId = openCampaign(5, true);

        List<CouponFacade.Issued> results = runTogether(List.of(
            () -> couponFacade.issue(campaignId, 42L, NOW),
            () -> couponFacade.issue(campaignId, 42L, NOW)));

        assertThat(results).extracting(CouponFacade.Issued::newlyIssued).containsExactlyInAnyOrder(true, false);
        assertThat(results).extracting(issued -> issued.coupon().id()).containsOnly(results.get(0).coupon().id());
        assertThat(couponRows(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @Nested
    @DisplayName("CPN-004: 이미 받은 사용자가 다시 발급하면")
    class Repeat {

        @DisplayName("같은 쿠폰을 돌려준다")
        @Test
        void returnsSameCoupon() {
            Long campaignId = openCampaign(5, true);
            Coupon first = couponFacade.issue(campaignId, 42L, NOW).coupon();

            CouponFacade.Issued second = couponFacade.issue(campaignId, 42L, NOW);

            assertThat(second.newlyIssued()).isFalse();
            assertThat(second.coupon().id()).isEqualTo(first.id());
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("품절 뒤에도 같은 쿠폰을 돌려준다")
        @Test
        void returnsSameCouponAfterSoldOut() {
            Long campaignId = openCampaign(1, true);
            Coupon first = couponFacade.issue(campaignId, 42L, NOW).coupon();

            CouponFacade.Issued again = couponFacade.issue(campaignId, 42L, NOW);

            assertThat(again.newlyIssued()).isFalse();
            assertThat(again.coupon().id()).isEqualTo(first.id());
        }
    }

    @DisplayName("CPN-005: 비활성 캠페인은 발급하지 않는다")
    @Test
    void rejectsInactiveCampaign() {
        Long campaignId = openCampaign(5, false);

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_CAMPAIGN_INACTIVE);
        assertThat(couponRows(campaignId)).isZero();
        assertThat(issuedCount(campaignId)).isZero();
    }

    @DisplayName("CPN-006: 없는 캠페인은 발급하지 않는다")
    @Test
    void rejectsUnknownCampaign() {
        assertThatThrownBy(() -> couponFacade.issue(999L, 42L, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_CAMPAIGN_NOT_FOUND);
        assertThat(couponRows(999L)).isZero();
    }

    @DisplayName("CPN-007: 보유 목록은 본인 쿠폰만 최신순으로 보여 준다")
    @Test
    void listsOwnCouponsNewestFirst() {
        Long blackFriday = openCampaign(5, true);
        Long cyberMonday = openCampaign(5, true);
        Long yearEnd = openCampaign(5, true);
        Coupon older = couponFacade.issue(blackFriday, 42L, NOW).coupon();
        Coupon sameTimeFirst = couponFacade.issue(cyberMonday, 42L, NOW.plusSeconds(60)).coupon();
        Coupon sameTimeSecond = couponFacade.issue(yearEnd, 42L, NOW.plusSeconds(60)).coupon();
        couponFacade.issue(blackFriday, 7L, NOW.plusSeconds(120));

        assertThat(couponFacade.findMine(42L)).extracting(Coupon::id)
            .containsExactly(sameTimeSecond.id(), sameTimeFirst.id(), older.id());
    }
}
