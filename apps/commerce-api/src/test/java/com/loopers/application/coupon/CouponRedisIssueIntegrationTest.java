package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.coupon.CouponRepositoryImpl;
import com.loopers.infrastructure.coupon.RedisCouponIssueGate;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "commerce.coupon.issue-mode=REDIS_SYNC")
class CouponRedisIssueIntegrationTest {
    @Autowired private CouponFacade facade;
    @Autowired private TestRestTemplate http;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;
    @MockitoSpyBean private CouponIssueTransaction transaction;
    @MockitoSpyBean private CouponRepositoryImpl coupons;
    @MockitoSpyBean private CouponCampaignRepositoryImpl campaignRepository;
    @MockitoSpyBean private RedisCouponIssueGate gate;
    @MockitoSpyBean private UserService userService;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(transaction, coupons, campaignRepository, gate, userService);
        for (Long id : campaignIds) {
            jdbc.update("DELETE FROM coupon WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaign WHERE id = ?", id);
            redis.delete(List.of(stock(id), "coupon-issue:{" + id + "}:users"));
        }
        users.deleteAllById(userIds);
    }

    @Test
    void redisPassStillReturnsActualCommittedCouponAndRepeat() {
        long campaign = campaign(5);
        long user = user();
        facade.prepareIssueGate(campaign);
        var first = facade.issue(user, campaign);
        assertThat(first.created()).isTrue();
        assertCounts(campaign, 1);
        assertThat(facade.issue(user, campaign).coupon().couponId()).isEqualTo(first.coupon().couponId());
        assertThat(remaining(campaign)).isEqualTo("4");
        assertThat(redis.opsForHash().get("coupon-issue:{" + campaign + "}:users", Long.toString(user))).isEqualTo("A");
    }

    @Test
    void soldOutAndUnpreparedDoNotEnterDatabaseIssuingTransaction() {
        long campaign = campaign(0);
        long user = user();
        assertRejected(() -> facade.issue(user, campaign), ErrorType.SERVICE_UNAVAILABLE);
        facade.prepareIssueGate(campaign);
        assertRejected(() -> facade.issue(user, campaign), ErrorType.CONFLICT);
        assertRejected(() -> facade.issue(user, Long.MAX_VALUE), ErrorType.NOT_FOUND);
        verify(transaction, never()).issue(any(), any(), any());
        assertCounts(campaign, 0);
    }

    @Test
    void failedInsertRollsBackAndCompensatesOnceBeforeRetry() {
        long campaign = campaign(5);
        long user = user();
        facade.prepareIssueGate(campaign);
        doThrow(new DataIntegrityViolationException("insert failed")).when(coupons).save(any());
        assertThatThrownBy(() -> facade.issue(user, campaign)).isInstanceOf(DataIntegrityViolationException.class);
        assertCounts(campaign, 0);
        assertThat(remaining(campaign)).isEqualTo("5");
        doCallRealMethod().when(coupons).save(any());
        assertThat(facade.issue(user, campaign).created()).isTrue();
        assertCounts(campaign, 1);
    }

    @Test
    void databaseRemainsFinalQuotaDefenseWhenRedisIsWrong() {
        long campaign = campaign(1);
        gate.prepare(campaign, 5, List.of(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        facade.issue(user(), campaign);
        assertRejected(() -> facade.issue(user(), campaign), ErrorType.CONFLICT);
        assertCounts(campaign, 1);
        assertThat(remaining(campaign)).isEqualTo("4");
    }

    @Test
    void databaseDeactivationRejectsAndCompensatesReservation() {
        long campaign = campaign(5);
        facade.prepareIssueGate(campaign);
        jdbc.update("UPDATE coupon_campaign SET active = false WHERE id = ?", campaign);
        assertRejected(() -> facade.issue(user(), campaign), ErrorType.CONFLICT);
        assertCounts(campaign, 0);
        assertThat(remaining(campaign)).isEqualTo("5");
    }

    @Test
    void redisFailureRejectsNewIssueButKeepsExistingResultAndList() {
        long campaign = campaign(5);
        long holder = user();
        facade.prepareIssueGate(campaign);
        long couponId = facade.issue(holder, campaign).coupon().couponId();
        clearInvocations(transaction);
        doThrow(new RedisConnectionFailureException("judgment failed")).when(gate).enter(any(), any(), any());
        assertRejected(() -> facade.issue(user(), campaign), ErrorType.SERVICE_UNAVAILABLE);
        assertThat(facade.issue(holder, campaign).coupon().couponId()).isEqualTo(couponId);
        assertThat(facade.getMyCoupons(holder)).hasSize(1);
        verify(transaction, never()).issue(any(), any(), any());
        assertCounts(campaign, 1);
    }

    @Test
    void redisConfirmationFailureDoesNotUndoCommittedCoupon() {
        long campaign = campaign(5);
        long user = user();
        facade.prepareIssueGate(campaign);
        doThrow(new RedisConnectionFailureException("confirm failed")).when(gate).confirm(any(), any(), any(), anyBoolean());
        var issued = facade.issue(user, campaign);
        assertThat(issued.created()).isTrue();
        assertThat(facade.issue(user, campaign).coupon().couponId()).isEqualTo(issued.coupon().couponId());
        assertCounts(campaign, 1);
        assertThat(remaining(campaign)).isEqualTo("4");
    }

    @Test
    void concurrentDuplicateIsInProgressUntilOriginalDatabaseCommit() throws Exception {
        long campaign = campaign(5);
        long user = user();
        facade.prepareIssueGate(campaign);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            holding.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(campaignRepository).find(campaign);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> facade.issue(user, campaign));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> facade.issue(user, campaign)).isInstanceOf(CoreException.class).hasMessage(CouponRejections.IN_PROGRESS);
            finish.countDown();
            long couponId = first.get(15, TimeUnit.SECONDS).coupon().couponId();
            assertThat(facade.issue(user, campaign).coupon().couponId()).isEqualTo(couponId);
            assertCounts(campaign, 1);
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void administratorPreparationAndSynchronousHttpContract() {
        long campaign = campaign(5);
        long user = user();
        String path = "/api-admin/v1/coupon-campaigns/" + campaign + "/issue-gate";
        assertThat(http.postForEntity(path, HttpEntity.EMPTY, JsonNode.class).getStatusCode().value()).isEqualTo(403);
        HttpHeaders admin = new HttpHeaders();
        admin.set("X-USER-ROLE", "ADMIN");
        assertThat(http.exchange(path, HttpMethod.POST, new HttpEntity<>(admin), JsonNode.class).getStatusCode().value()).isEqualTo(200);
        HttpHeaders owner = new HttpHeaders();
        owner.set("X-USER-ID", Long.toString(user));
        var issued = http.exchange("/api/v1/coupon-campaigns/" + campaign + "/issues", HttpMethod.POST,
            new HttpEntity<>(owner), JsonNode.class);
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        assertThat(issued.getBody().path("data").path("couponId").asLong()).isPositive();
        var repeated = http.exchange("/api/v1/coupon-campaigns/" + campaign + "/issues", HttpMethod.POST,
            new HttpEntity<>(owner), JsonNode.class);
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody().path("data")).isEqualTo(issued.getBody().path("data"));
    }

    @Test
    void failedCompensationIsRecoveredOnlyAfterQuiescentDatabasePreparation() {
        long campaign = campaign(5);
        long user = user();
        facade.prepareIssueGate(campaign);
        doThrow(new DataIntegrityViolationException("insert failed")).when(coupons).save(any());
        doThrow(new RedisConnectionFailureException("compensation failed")).when(gate).cancel(any(), any(), any());
        assertThatThrownBy(() -> facade.issue(user, campaign)).isInstanceOf(DataIntegrityViolationException.class);
        assertCounts(campaign, 0);
        assertThat(remaining(campaign)).isEqualTo("4");
        doCallRealMethod().when(coupons).save(any());
        doCallRealMethod().when(gate).cancel(any(), any(), any());
        assertThatThrownBy(() -> facade.issue(user, campaign)).hasMessage(CouponRejections.IN_PROGRESS);
        // 이 테스트가 시작한 발급 호출은 전부 끝났고 DB rollback을 확인했다. 그 뒤에만 재준비한다.
        facade.prepareIssueGate(campaign);
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(facade.issue(user, campaign).created()).isTrue();
        assertCounts(campaign, 1);
    }

    /** Redis가 먼저 판단한다. 매진·종료처럼 결과가 정해진 요청은 사용자·기존 쿠폰 조회도 하지 않고 DB에 가지 않는다. */
    @Test
    void decidedRejectionsDoNotQueryDatabaseBeforeRedis() {
        long soldOut = campaign(1);
        facade.prepareIssueGate(soldOut);
        facade.issue(user(), soldOut);
        long late = user();
        clearInvocations(transaction, coupons, userService);

        assertRejected(() -> facade.issue(late, soldOut), ErrorType.CONFLICT);

        verify(userService, never()).get(any());
        verify(coupons, never()).findByCampaignIdAndUserId(any(), any());
        verify(transaction, never()).issue(any(), any(), any());
    }

    /** 없는 사용자는 Redis를 통과해도 DB 발급 전에 404로 막고, 가져간 자리를 돌려준다. */
    @Test
    void unknownUserPassingRedisIsRejectedAndReservationReturned() {
        long campaign = campaign(1);
        facade.prepareIssueGate(campaign);

        assertRejected(() -> facade.issue(Long.MAX_VALUE, campaign), ErrorType.NOT_FOUND);

        verify(transaction, never()).issue(any(), any(), any());
        assertThat(remaining(campaign)).isEqualTo("1");
        assertThat(facade.issue(user(), campaign).created()).isTrue();
        assertCounts(campaign, 1);
    }

    private long campaign(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("Redis 기준선", "혜택", true, quota, Instant.now().plusSeconds(3600))).getId();
        campaignIds.add(id);
        return id;
    }
    private long user() { long id = users.saveAndFlush(new UserModel()).getId(); userIds.add(id); return id; }
    private String stock(long id) { return "coupon-issue:{" + id + "}:stock"; }
    private Object remaining(long id) { return redis.opsForHash().get(stock(id), "remaining"); }
    private void assertCounts(long campaign, long count) {
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, campaign)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaign)).isEqualTo(count);
    }
    private static void assertRejected(Runnable call, ErrorType type) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreException.class, failure -> assertThat(failure.getErrorType()).isEqualTo(type));
    }
}
