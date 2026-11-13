package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.coupon.RedisCouponIssueGate;
import com.loopers.infrastructure.user.UserJpaRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 작업 큐 Worker의 묶음 저장. 가져온 작업들을 한 DB TX로 저장한다.
 * 작업마다 결과(발급·기존 쿠폰·확정 거절)를 나눠 받아, 한 작업의 거절이 다른 작업의 저장을 되돌리지 않는다.
 */
@SpringBootTest(properties = {"commerce.coupon.issue-mode=QUEUE", "commerce.coupon.issue-queue.worker.enabled=false", "commerce.coupon.issue-queue.worker.backoff-initial=0ms",
    "commerce.coupon.issue-queue.worker.save-in-batch=true", "commerce.coupon.issue-queue.worker.batch-size=10"})
class CouponQueueBatchIssueIntegrationTest {
    @Autowired private CouponFacade facade;
    @Autowired private CouponIssueWorker worker;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;
    @MockitoSpyBean private CouponIssueTransaction transaction;
    @MockitoSpyBean private RedisCouponIssueGate gate;
    @MockitoSpyBean private CouponCampaignRepositoryImpl campaignRepository;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(transaction, gate, campaignRepository);
        for (Long id : campaignIds) {
            jdbc.update("DELETE FROM coupon WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_issue_cancellation WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaign WHERE id = ?", id);
            redis.delete(List.of(RedisCouponIssueGate.stockKey(id), RedisCouponIssueGate.usersKey(id), RedisCouponIssueGate.jobsKey(id)));
            redis.opsForSet().remove("coupon-issue:queued-campaigns", id.toString());
        }
        users.deleteAllById(userIds);
    }

    /** 다섯 작업을 TX 한 번으로 저장하고 모두 확정·처리 완료한다. */
    @Test
    void savesSeveralJobsInOneTransaction() {
        long campaign = prepared(10);
        for (int i = 0; i < 5; i++) facade.issue(user(), campaign);

        assertThat(worker.processOnce()).isEqualTo(5);

        verify(transaction, times(1)).issueAcceptedBatch(any(), any());
        // 발급 수는 묶음당 한 번(+5) 올린다. 쿠폰마다 올리지 않는다.
        verify(campaignRepository, times(1)).tryRecordIssues(eq(campaign), eq(5), any());
        verify(campaignRepository, never()).tryRecordIssue(any(), any());
        assertThat(couponRows(campaign)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, campaign)).isEqualTo(5);
        assertThat(pendingCount(campaign)).isZero();
        assertThat(remaining(campaign)).isEqualTo("5");
    }

    /** 같은 묶음에 없는 사용자가 섞여도 나머지는 저장되고, 없는 사용자만 취소되어 수량이 돌아온다. */
    @Test
    void unknownUserInBatchIsCancelledWithoutUndoingOthers() {
        long campaign = prepared(10);
        facade.issue(user(), campaign);
        facade.issue(987_654_321L, campaign);
        facade.issue(user(), campaign);

        assertThat(worker.processOnce()).isEqualTo(3);

        assertThat(couponRows(campaign)).isEqualTo(2);
        assertThat(facade.getIssueStatus(987_654_321L, campaign).cancelReason()).isEqualTo("USER_NOT_FOUND");
        assertThat(remaining(campaign)).isEqualTo("8");
        assertThat(pendingCount(campaign)).isZero();
    }

    /** DB가 한도에 닿으면(Redis가 실제보다 많이 남았다고 본 경우) 넘친 작업만 매진으로 취소되고 앞의 작업은 저장된다. */
    @Test
    void databaseQuotaRejectsOnlyOverflowingJobsInBatch() {
        long campaign = prepared(3);
        List<Long> requesters = List.of(user(), user(), user());
        for (Long requester : requesters) facade.issue(requester, campaign);
        jdbc.update("UPDATE coupon_campaign SET total_quota = 2 WHERE id = ?", campaign);

        assertThat(worker.processOnce()).isEqualTo(3);

        // 세 장을 한 번에 올릴 수 없어 쿠폰마다 조건부 UPDATE로 나눈다.
        verify(campaignRepository, times(1)).tryRecordIssues(eq(campaign), eq(3), any());
        verify(campaignRepository, times(3)).tryRecordIssue(eq(campaign), any());
        assertThat(couponRows(campaign)).isEqualTo(2);
        assertThat(facade.getIssueStatus(requesters.get(2), campaign).cancelReason()).isEqualTo("SOLD_OUT");
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, campaign)).isEqualTo(2);
    }

    /** 묶음 TX가 일시 오류로 실패하면 모두 처리 완료하지 않고, 다시 처리하면 한 장씩만 생긴다. */
    @Test
    void failedBatchIsRetriedWithoutDuplicates() {
        long campaign = prepared(10);
        for (int i = 0; i < 3; i++) facade.issue(user(), campaign);
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAcceptedBatch(any(), any());
        doThrow(new DataAccessResourceFailureException("db down")).when(transaction).issueAccepted(any(), any(), any(), any(), any());

        assertThat(worker.processOnce()).isZero();
        assertThat(couponRows(campaign)).isZero();
        assertThat(pendingCount(campaign)).isEqualTo(3);

        reset(transaction);
        assertThat(worker.processOnce()).isEqualTo(3);
        assertThat(couponRows(campaign)).isEqualTo(3);
        assertThat(remaining(campaign)).isEqualTo("7");
        assertThat(pendingCount(campaign)).isZero();
    }

    /** 묶음 저장은 됐는데 예약 확정이 실패하면, 다시 처리할 때 이 작업들이 만든 쿠폰으로 보고 수량을 돌려주지 않는다. */
    @Test
    void retryAfterBatchCommitDoesNotReturnIssuedQuantity() {
        long campaign = prepared(10);
        for (int i = 0; i < 2; i++) facade.issue(user(), campaign);
        doThrow(new RedisConnectionFailureException("confirm failed")).when(gate).confirm(any(), any(), any(), anyBoolean());

        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(2);
        assertThat(pendingCount(campaign)).isEqualTo(2);

        reset(gate);
        worker.processOnce();
        assertThat(couponRows(campaign)).isEqualTo(2);
        assertThat(remaining(campaign)).isEqualTo("8");
        assertThat(pendingCount(campaign)).isZero();
    }

    private long prepared(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("묶음 저장", "5,000원 할인권", true, quota,
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

    private long pendingCount(long id) {
        return redis.opsForStream().pending(RedisCouponIssueGate.jobsKey(id), RedisCouponIssueGate.JOB_GROUP).getTotalPendingMessages();
    }

    private long couponRows(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, id);
    }
}
