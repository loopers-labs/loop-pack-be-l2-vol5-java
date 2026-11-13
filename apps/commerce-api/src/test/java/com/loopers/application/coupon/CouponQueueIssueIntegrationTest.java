package com.loopers.application.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssueGate;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 작업 큐 모드(QUEUE). 요청은 Redis Lua 한 번으로 판단 + Stream 등록을 하고 202로 끝난다.
 * DB 저장은 Worker가 한다. 테스트는 Worker 스레드를 끄고 processOnce()를 직접 불러 순서를 정한다.
 * 여기서는 한 건씩 저장하는 경로를 본다. 묶음 저장은 CouponQueueBatchIssueIntegrationTest가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "commerce.coupon.issue-mode=QUEUE", "commerce.coupon.issue-queue.worker.enabled=false", "commerce.coupon.issue-queue.worker.backoff-initial=0ms",
    "commerce.coupon.issue-queue.worker.save-in-batch=false"})
class CouponQueueIssueIntegrationTest {
    @Autowired private CouponFacade facade;
    @Autowired private CouponIssueWorker worker;
    @Autowired private TestRestTemplate http;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private CouponJpaRepository coupons;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;
    @MockitoSpyBean private CouponIssueTransaction transaction;
    @MockitoSpyBean private RedisCouponIssueGate gate;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(transaction, gate);
        for (Long id : campaignIds) {
            jdbc.update("DELETE FROM coupon WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_issue_cancellation WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaign WHERE id = ?", id);
            redis.delete(List.of(stock(id), users(id), jobs(id), RedisCouponIssueGate.heldKey(id)));
            redis.opsForSet().remove("coupon-issue:queued-campaigns", id.toString());
        }
        users.deleteAllById(userIds);
    }

    /** 접수하면 쿠폰은 아직 없고 작업이 하나 쌓인다. Worker가 처리하면 쿠폰이 생기고 예약이 확정되며 작업은 처리 완료된다. */
    @Test
    void acceptsWithoutDatabaseThenWorkerIssuesAndAcknowledges() {
        long campaign = prepared(5);
        long user = user();

        CouponIssueResult accepted = facade.issue(user, campaign);

        assertThat(accepted.pending()).isTrue();
        assertThat(couponRows(campaign)).isZero();
        assertThat(jobCount(campaign)).isEqualTo(1);

        assertThat(worker.processOnce()).isEqualTo(1);

        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(redis.opsForHash().get(users(campaign), Long.toString(user))).isEqualTo("A");
        assertThat(pendingCount(campaign)).isZero();
        assertThat(remaining(campaign)).isEqualTo("4");
        assertThat(facade.getIssueStatus(user, campaign).state()).isEqualTo(CouponIssueStatus.State.ISSUED);
    }

    /** 매진이면 작업을 쌓지 않고 바로 409다. */
    @Test
    void soldOutIsRejectedWithoutJob() {
        long campaign = prepared(1);
        facade.issue(user(), campaign);

        assertRejected(() -> facade.issue(user(), campaign), ErrorType.CONFLICT);
        assertThat(jobCount(campaign)).isEqualTo(1);
    }

    /** 처리 중에 다시 누르면 작업을 또 쌓지 않는다. 저장된 뒤에는 같은 쿠폰을 돌려준다. */
    @Test
    void repeatWhilePendingDoesNotEnqueueAgain() {
        long campaign = prepared(5);
        long user = user();

        assertThat(facade.issue(user, campaign).pending()).isTrue();
        assertThat(facade.issue(user, campaign).pending()).isTrue();
        assertThat(jobCount(campaign)).isEqualTo(1);

        worker.processOnce();
        CouponIssueResult repeated = facade.issue(user, campaign);
        assertThat(repeated.pending()).isFalse();
        assertThat(repeated.created()).isFalse();
        assertThat(couponRows(campaign)).isEqualTo(1);
    }

    /** 없는 사용자는 접수는 되지만 Worker가 확정 거절한다. 취소 기록을 남기고 수량을 돌려주며 작업은 처리 완료다. */
    @Test
    void unknownUserIsCancelledAndReservationReturned() {
        long campaign = prepared(5);
        long unknown = 987_654_321L;

        assertThat(facade.issue(unknown, campaign).pending()).isTrue();
        worker.processOnce();

        CouponIssueStatus status = facade.getIssueStatus(unknown, campaign);
        assertThat(status.state()).isEqualTo(CouponIssueStatus.State.CANCELLED);
        assertThat(status.cancelReason()).isEqualTo("USER_NOT_FOUND");
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(pendingCount(campaign)).isZero();
        assertThat(couponRows(campaign)).isZero();
    }

    /** DB 일시 오류면 처리 완료로 표시하지 않고 다시 처리한다. 다시 처리하면 한 장만 생긴다. */
    @Test
    void transientDatabaseFailureIsRetried() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        doThrow(new DataAccessResourceFailureException("db down")).doCallRealMethod()
            .when(transaction).issueAccepted(any(), any(), any(), any(), any());

        worker.processOnce();
        assertThat(couponRows(campaign)).isZero();
        assertThat(pendingCount(campaign)).isEqualTo(1);

        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(pendingCount(campaign)).isZero();
        assertThat(remaining(campaign)).isEqualTo("4");
    }

    /**
     * DB 저장은 됐는데 예약 확정 전에 멈춘 경우(여기서는 확정 실패). 다시 처리하면 DB에는 이 작업이 만든 쿠폰이 있다.
     * 이를 "기존 쿠폰"으로 보고 수량을 돌려주면 이미 나간 한 장이 다시 생긴다. 돌려주지 않고 확정만 한다.
     */
    @Test
    void retryAfterCommitDoesNotReturnIssuedQuantity() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        doThrow(new RedisConnectionFailureException("confirm failed")).doCallRealMethod()
            .when(gate).confirm(any(), any(), any(), anyBoolean());

        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(pendingCount(campaign)).isEqualTo(1);

        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(remaining(campaign)).isEqualTo("4");
        assertThat(redis.opsForHash().get(users(campaign), Long.toString(user))).isEqualTo("A");
        assertThat(pendingCount(campaign)).isZero();
    }

    /** Redis는 모르고 DB에만 있던 쿠폰(접수 전부터 있음)이면 이번 예약의 수량은 돌려준다. */
    @Test
    void couponThatExistedBeforeAcceptanceReturnsReservedQuantity() {
        long campaign = prepared(5);
        long user = user();
        coupons.saveAndFlush(new CouponModel(user, campaign, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), "5,000원 할인권"));
        facade.issue(user, campaign);

        worker.processOnce();

        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(redis.opsForHash().get(users(campaign), Long.toString(user))).isEqualTo("A");
    }

    /** 종료 전에 접수한 요청은 종료 뒤에 처리돼도 발급한다. 발급 시각은 접수 시각이다. */
    @Test
    void acceptedBeforeEndIsIssuedEvenIfProcessedAfterEnd() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        jdbc.update("UPDATE coupon_campaign SET end_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), campaign);

        worker.processOnce();

        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(facade.getIssueStatus(user, campaign).state()).isEqualTo(CouponIssueStatus.State.ISSUED);
    }

    /** HTTP: 접수 202 PENDING → 상태 조회 PENDING → 처리 뒤 상태 ISSUED, 다시 요청하면 200과 쿠폰. */
    @Test
    void httpContractAcceptsThenReportsStatus() {
        long campaign = prepared(5);
        long user = user();

        ResponseEntity<JsonNode> accepted = post(campaign, user);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(status(campaign, user).path("status").asText()).isEqualTo("PENDING");

        worker.processOnce();

        JsonNode issued = status(campaign, user);
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        ResponseEntity<JsonNode> repeated = post(campaign, user);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().path("data").path("couponId").asLong()).isEqualTo(issued.path("coupon").path("couponId").asLong());
        assertThat(status(campaign, user()).path("status").asText()).isEqualTo("NONE");
    }

    private ResponseEntity<JsonNode> post(long campaign, long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(user));
        return http.exchange("/api/v1/coupon-campaigns/" + campaign + "/issues", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode status(long campaign, long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(user));
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/coupon-campaigns/" + campaign + "/issues/me", HttpMethod.GET,
            new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data");
    }

    private long prepared(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("큐 발급", "5,000원 할인권", true, quota,
            Instant.now().plusSeconds(3600))).getId();
        campaignIds.add(id);
        facade.prepareIssueGate(id);
        return id;
    }

    private long user() {
        long id = users.saveAndFlush(new UserModel()).getId();
        userIds.add(id);
        return id;
    }

    private static String stock(long id) { return RedisCouponIssueGate.stockKey(id); }
    private static String users(long id) { return RedisCouponIssueGate.usersKey(id); }
    private static String jobs(long id) { return RedisCouponIssueGate.jobsKey(id); }
    private String remaining(long id) { return (String) redis.opsForHash().get(stock(id), "remaining"); }
    private long jobCount(long id) { return redis.opsForStream().size(jobs(id)); }
    private long pendingCount(long id) {
        return redis.opsForStream().pending(jobs(id), RedisCouponIssueGate.JOB_GROUP).getTotalPendingMessages();
    }
    private long couponRows(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, id);
    }

    private static void assertRejected(Runnable call, ErrorType type) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(type));
    }
}
