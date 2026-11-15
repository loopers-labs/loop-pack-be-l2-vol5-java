package com.loopers.application.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.coupon.CouponRepositoryImpl;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 재시작 기준선: 실제 MySQL에서 동기 발급·원자성·HTTP 계약을 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "commerce.coupon.issue-mode=DB_ONLY")
class CouponIssueIntegrationTest {
    @Autowired private CouponFacade facade;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @MockitoSpyBean private CouponRepositoryImpl coupons;
    @MockitoSpyBean private CouponCampaignRepositoryImpl campaignRepository;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(coupons, campaignRepository);
        for (Long id : campaignIds) {
            jdbc.update("DELETE FROM coupon WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaign WHERE id = ?", id);
        }
        users.deleteAllById(userIds);
    }

    @Test
    void issuesAndReturnsSameCouponOnRetry() {
        long campaign = campaign(5, true, Instant.now().plusSeconds(3600));
        long user = user();
        var first = issue(campaign, user);
        var repeated = issue(campaign, user);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().path("data").path("couponId").asLong()).isPositive();
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody().path("data")).isEqualTo(first.getBody().path("data"));
        assertCounts(campaign, 1);
        assertThat(facade.getMyCoupons(user)).hasSize(1);
    }

    @Test
    void rejectionsDistinguishSoldOutInactiveEndedAndMissing() {
        long soldOut = campaign(1, true, Instant.now().plusSeconds(3600));
        long holder = user();
        assertThat(issue(soldOut, holder).getStatusCode().value()).isEqualTo(201);
        assertThat(issue(soldOut, user()).getBody().path("meta").path("message").asText()).isEqualTo(CouponRejections.SOLD_OUT);
        assertThat(issue(campaign(5, false, Instant.now().plusSeconds(3600)), user()).getStatusCode().value()).isEqualTo(409);
        assertThat(issue(campaign(5, true, Instant.now().minusSeconds(1)), user()).getBody().path("meta").path("message").asText())
            .isEqualTo(CouponRejections.ENDED);
        assertThat(issue(Long.MAX_VALUE, user()).getStatusCode().value()).isEqualTo(404);
        assertCounts(soldOut, 1);
        assertThat(issue(soldOut, holder).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void existingCouponSurvivesCampaignDeactivationAndEnd() {
        long campaign = campaign(1, true, Instant.now().plusSeconds(3600));
        long user = user();
        long couponId = facade.issue(user, campaign).coupon().couponId();
        jdbc.update("UPDATE coupon_campaign SET active = false, end_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), campaign);
        assertThat(facade.issue(user, campaign).coupon().couponId()).isEqualTo(couponId);
        assertCounts(campaign, 1);
    }

    @Test
    void validatesCurrentUserAndCampaignId() {
        long campaign = campaign(5, true, Instant.now().plusSeconds(3600));
        assertThat(issue(campaign, null).getStatusCode().value()).isEqualTo(400);
        assertThat(issue(campaign, -1L).getStatusCode().value()).isEqualTo(400);
        assertThat(issue(campaign, Long.MAX_VALUE).getStatusCode().value()).isEqualTo(404);
        assertThat(issue(-1L, user()).getStatusCode().value()).isEqualTo(400);
        assertCounts(campaign, 0);
    }

    @Test
    void twentyUsersCompeteForExactlyFiveCoupons() throws Exception {
        long campaign = campaign(5, true, Instant.now().plusSeconds(3600));
        List<Long> requesters = new ArrayList<>();
        for (int i = 0; i < 20; i++) requesters.add(user());
        var executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (Long requester : requesters) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        facade.issue(requester, campaign);
                        return true;
                    } catch (CoreException failure) {
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                        assertThat(failure.getMessage()).isEqualTo(CouponRejections.SOLD_OUT);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long issued = 0;
            for (var task : tasks) if (task.get(30, TimeUnit.SECONDS)) issued++;
            assertThat(issued).isEqualTo(5);
            assertCounts(campaign, 5);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM coupon WHERE campaign_id = ?", Long.class, campaign)).isEqualTo(5);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sameUserCompetingForLastCouponGetsSameCoupon() throws Exception {
        assertConcurrentDuplicate(1);
    }

    @Test
    void sameUserUniqueConflictRollsBackExtraCount() throws Exception {
        assertConcurrentDuplicate(5);
    }

    private void assertConcurrentDuplicate(int quota) throws Exception {
        long campaign = campaign(quota, true, Instant.now().plusSeconds(3600));
        long user = user();
        CountDownLatch bothRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            bothRead.countDown();
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            return found;
        }).when(campaignRepository).find(campaign);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> facade.issue(user, campaign).coupon());
            var second = executor.submit(() -> facade.issue(user, campaign).coupon());
            assertThat(first.get(20, TimeUnit.SECONDS).couponId()).isEqualTo(second.get(20, TimeUnit.SECONDS).couponId());
            assertCounts(campaign, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void insertFailureRollsBackCountAndRetrySucceeds() {
        long campaign = campaign(5, true, Instant.now().plusSeconds(3600));
        long user = user();
        doThrow(new DataIntegrityViolationException("coupon insert failed")).when(coupons).save(any());
        assertThatThrownBy(() -> facade.issue(user, campaign)).isInstanceOf(DataIntegrityViolationException.class);
        assertCounts(campaign, 0);
        doCallRealMethod().when(coupons).save(any());
        facade.issue(user, campaign);
        assertCounts(campaign, 1);
    }

    @Test
    void myCouponsArePrivateAndSortedByTimeThenId() {
        long user = user();
        long other = user();
        long campaign1 = campaign(5, true, Instant.now().plusSeconds(3600));
        long campaign2 = campaign(5, true, Instant.now().plusSeconds(3600));
        long first = facade.issue(user, campaign1).coupon().couponId();
        long second = facade.issue(user, campaign2).coupon().couponId();
        facade.issue(other, campaign1);
        var tie = java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("UPDATE coupon SET issued_at = ? WHERE id IN (?, ?)", tie, first, second);
        assertThat(facade.getMyCoupons(user).stream().map(CouponInfo::couponId).toList()).containsExactly(second, first);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(user));
        var response = http.exchange("/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("data").size()).isEqualTo(2);
        assertThat(response.getBody().path("data").get(0).path("couponId").asLong()).isEqualTo(second);
    }

    private long campaign(int quota, boolean active, Instant endAt) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("기준선", "5,000원 할인권", active, quota, endAt)).getId();
        campaignIds.add(id);
        return id;
    }

    private long user() {
        long id = users.saveAndFlush(new UserModel()).getId();
        userIds.add(id);
        return id;
    }

    private void assertCounts(long campaign, long count) {
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, campaign)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaign)).isEqualTo(count);
    }

    private ResponseEntity<JsonNode> issue(long campaign, Long user) {
        HttpHeaders headers = new HttpHeaders();
        if (user != null) headers.set("X-USER-ID", user.toString());
        return http.exchange("/api/v1/coupon-campaigns/" + campaign + "/issues", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
    }
}
