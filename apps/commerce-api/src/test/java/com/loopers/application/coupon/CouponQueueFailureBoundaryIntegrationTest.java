package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssueGate;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 작업 큐의 장애 경계(검토의 P1·P2). 접수한 작업이 사라지거나, 확정한 결과(발급·취소)가 재처리에서 뒤집히거나,
 * 실패한 작업 하나가 뒤 작업을 막지 않는지 본다. 재시도 대기는 0으로 두고 processOnce()를 직접 부른다.
 */
@SpringBootTest(properties = {"commerce.coupon.issue-mode=QUEUE", "commerce.coupon.issue-queue.worker.enabled=false",
    "commerce.coupon.issue-queue.worker.save-in-batch=true", "commerce.coupon.issue-queue.worker.max-attempts=3",
    "commerce.coupon.issue-queue.worker.backoff-initial=0ms"})
class CouponQueueFailureBoundaryIntegrationTest {
    @Autowired private CouponFacade facade;
    @Autowired private CouponIssueWorker worker;
    @Autowired private CouponCampaignJpaRepository campaigns;
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
            redis.delete(List.of(RedisCouponIssueGate.stockKey(id), RedisCouponIssueGate.usersKey(id),
                RedisCouponIssueGate.jobsKey(id), RedisCouponIssueGate.heldKey(id)));
            redis.opsForSet().remove("coupon-issue:queued-campaigns", id.toString());
        }
        users.deleteAllById(userIds);
    }

    // ---- P1-1. 미완료 작업은 만료로 사라지지 않는다 ----

    /** 작업 큐에는 만료를 걸지 않는다. 처리 전 작업이 시간만으로 사라지지 않는다. */
    @Test
    void jobQueueHasNoExpiry() {
        long campaign = prepared(5);
        assertThat(redis.getExpire(RedisCouponIssueGate.jobsKey(campaign))).isEqualTo(-1L);
    }

    /** 행사가 끝나도 처리 안 된 작업이 남아 있으면 큐를 지우지 않는다. 다 처리된 뒤에야 정리한다. */
    @Test
    void endedCampaignKeepsQueueUntilAllJobsAreProcessed() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        endInRedis(campaign);
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAcceptedBatch(any(), any());
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAccepted(any(), any(), any(), any(), any());

        worker.processOnce();
        worker.processOnce();
        assertThat(redis.hasKey(RedisCouponIssueGate.jobsKey(campaign))).isTrue();
        assertThat(redis.opsForSet().isMember("coupon-issue:queued-campaigns", Long.toString(campaign))).isTrue();

        reset(transaction);
        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);

        worker.processOnce();
        assertThat(redis.hasKey(RedisCouponIssueGate.jobsKey(campaign))).isFalse();
        assertThat(redis.opsForSet().isMember("coupon-issue:queued-campaigns", Long.toString(campaign))).isFalse();
        assertThat(facade.getIssueStatus(user, campaign).state()).isEqualTo(CouponIssueStatus.State.ISSUED);
    }

    // ---- P1-2. 접수 Lua는 쓰기 전에 큐 상태를 확인한다 ----

    /** 큐가 준비되지 않았으면 수량·사용자 기록을 바꾸지 않고 준비 안 됨(503)이다. 다시 눌러도 처리 중이라고 하지 않는다. */
    @Test
    void missingQueueRejectsWithoutReservation() {
        long campaign = prepared(5);
        redis.delete(RedisCouponIssueGate.jobsKey(campaign));
        long user = user();

        assertRejected(() -> facade.issue(user, campaign), ErrorType.SERVICE_UNAVAILABLE);
        assertRejected(() -> facade.issue(user, campaign), ErrorType.SERVICE_UNAVAILABLE);
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(redis.opsForHash().hasKey(RedisCouponIssueGate.usersKey(campaign), Long.toString(user))).isFalse();
    }

    /** 큐 키가 다른 타입이면 오류지만, 쓰기 전에 멈춰 수량·사용자 기록이 남지 않는다. */
    @Test
    void wrongQueueTypeFailsBeforeAnyWrite() {
        long campaign = prepared(5);
        redis.delete(RedisCouponIssueGate.jobsKey(campaign));
        redis.opsForValue().set(RedisCouponIssueGate.jobsKey(campaign), "broken");
        long user = user();

        assertRejected(() -> facade.issue(user, campaign), ErrorType.SERVICE_UNAVAILABLE);
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(redis.opsForHash().hasKey(RedisCouponIssueGate.usersKey(campaign), Long.toString(user))).isFalse();
    }

    // ---- P2-3. 확정한 취소는 재처리에서 유지한다 ----

    /** 취소 기록을 남긴 뒤 Redis 보상이 실패하고 캠페인이 다시 열려도, 재처리는 취소를 유지하고 발급하지 않는다. */
    @Test
    void recordedCancellationSurvivesRetryAfterConditionChanges() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        jdbc.update("UPDATE coupon_campaign SET active = false WHERE id = ?", campaign);
        doThrow(new RedisConnectionFailureException("cancel failed")).when(gate).cancelFinally(any(), any(), any());

        worker.processOnce();
        assertThat(facade.getIssueStatus(user, campaign).state()).isEqualTo(CouponIssueStatus.State.CANCELLED);

        reset(gate);
        jdbc.update("UPDATE coupon_campaign SET active = true WHERE id = ?", campaign);
        worker.processOnce();

        assertThat(couponRows(campaign)).isZero();
        assertThat(facade.getIssueStatus(user, campaign).cancelReason()).isEqualTo("INACTIVE");
        assertThat(remaining(campaign)).isEqualTo("5");
        assertThat(pendingCount(campaign)).isZero();
    }

    /** 취소된 사람이 다시 신청하면 받지 않는다(409). 작업도 새로 쌓지 않는다. */
    @Test
    void cancelledUserCannotApplyAgain() {
        long campaign = prepared(5);
        long unknown = 987_654_321L;
        facade.issue(unknown, campaign);
        worker.processOnce();

        assertRejected(() -> facade.issue(unknown, campaign), ErrorType.CONFLICT);
        assertThat(jobCount(campaign)).isEqualTo(1);
        assertThat(remaining(campaign)).isEqualTo("5");
    }

    // ---- P2-4. 이미 성공한 발급은 재처리에서 환불하지 않는다 ----

    /** 저장 뒤 확정 전에 멈춘 사이 사용자가 지워져도, 재처리는 이미 있는 쿠폰을 확정할 뿐 취소·환불하지 않는다. */
    @Test
    void issuedCouponIsNotRefundedWhenUserDisappearsBeforeRetry() {
        long campaign = prepared(5);
        long user = user();
        facade.issue(user, campaign);
        doThrow(new RedisConnectionFailureException("confirm failed")).when(gate).confirm(any(), any(), any(), anyBoolean());

        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);
        reset(gate);
        users.deleteById(user);

        worker.processOnce();
        assertThat(remaining(campaign)).isEqualTo("4");
        assertThat(facade.getIssueStatus(user, campaign).state()).isEqualTo(CouponIssueStatus.State.ISSUED);
        assertThat(pendingCount(campaign)).isZero();
    }

    // ---- P2-5. 실패한 작업 하나가 뒤 작업을 막지 않는다 ----

    /** 특정 작업만 계속 실패하면 정해진 횟수 뒤 보류함으로 옮기고, 뒤 작업은 처리한다. 보류된 사람의 예약은 지우지 않는다. */
    @Test
    void repeatedlyFailingJobIsHeldAndDoesNotBlockOthers() {
        long campaign = prepared(5);
        long poisoned = user();
        long next = user();
        facade.issue(poisoned, campaign);
        facade.issue(next, campaign);
        doAnswer(invocation -> {
            List<?> jobs = invocation.getArgument(0);
            if (jobs.stream().anyMatch(job -> ((com.loopers.domain.coupon.CouponIssueJob) job).userId() == poisoned)) {
                throw new IllegalStateException("bad data");
            }
            return invocation.callRealMethod();
        }).when(transaction).issueAcceptedBatch(any(), any());
        doAnswer(invocation -> {
            if (((Long) invocation.getArgument(0)) == poisoned) throw new IllegalStateException("bad data");
            return invocation.callRealMethod();
        }).when(transaction).issueAccepted(any(), any(), any(), any(), any());

        for (int i = 0; i < 4; i++) worker.processOnce();

        assertThat(couponRows(campaign)).isEqualTo(1);
        assertThat(facade.getIssueStatus(next, campaign).state()).isEqualTo(CouponIssueStatus.State.ISSUED);
        assertThat(redis.opsForHash().size(RedisCouponIssueGate.heldKey(campaign))).isEqualTo(1);
        assertThat(pendingCount(campaign)).isZero();
        assertThat(facade.getIssueStatus(poisoned, campaign).state()).isEqualTo(CouponIssueStatus.State.PENDING);
        assertThat(remaining(campaign)).isEqualTo("3");
    }

    /** DB 연결 장애처럼 모든 작업이 실패하는 경우는 횟수로 보류하지 않는다. 복구되면 이어서 처리한다. */
    @Test
    void infrastructureFailureIsRetriedWithoutHolding() {
        long campaign = prepared(5);
        facade.issue(user(), campaign);
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAcceptedBatch(any(), any());
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAccepted(any(), any(), any(), any(), any());

        for (int i = 0; i < 5; i++) worker.processOnce();
        assertThat(redis.opsForHash().size(RedisCouponIssueGate.heldKey(campaign))).isZero();
        assertThat(pendingCount(campaign)).isEqualTo(1);

        reset(transaction);
        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(1);
    }

    private void endInRedis(long campaign) {
        redis.opsForHash().put(RedisCouponIssueGate.stockKey(campaign), "endAt", Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
    }

    private long prepared(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("장애 경계", "5,000원 할인권", true, quota,
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

    private String remaining(long id) {
        return (String) redis.opsForHash().get(RedisCouponIssueGate.stockKey(id), "remaining");
    }

    private long jobCount(long id) {
        return redis.opsForStream().size(RedisCouponIssueGate.jobsKey(id));
    }

    private long pendingCount(long id) {
        return redis.opsForStream().pending(RedisCouponIssueGate.jobsKey(id), RedisCouponIssueGate.JOB_GROUP).getTotalPendingMessages();
    }

    private long couponRows(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, id);
    }

    private static void assertRejected(Runnable call, ErrorType type) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(type));
    }
}
