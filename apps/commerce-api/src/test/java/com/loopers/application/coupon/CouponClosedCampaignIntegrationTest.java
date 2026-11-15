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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 로컬 매진 캐시. 남은 수가 0이고 처리 중인 예약도 0인 "다시 풀리지 않는 매진"만 서버 메모리에 기억해,
 * 그 뒤의 신청은 Redis에 가지 않고 409로 끝낸다. 예약이 남아 있는 동안은 자리가 돌아올 수 있어 기억하지 않는다.
 */
@SpringBootTest(properties = {"commerce.coupon.issue-mode=QUEUE", "commerce.coupon.issue-queue.worker.enabled=false",
    "commerce.coupon.issue-queue.worker.backoff-initial=0ms"})
class CouponClosedCampaignIntegrationTest {
    private static final long UNKNOWN_USER = 987_650_001L;
    @Autowired private CouponFacade facade;
    @Autowired private CouponIssueWorker worker;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;
    @MockitoSpyBean private RedisCouponIssueGate gate;
    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reset(gate);
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

    /** 남은 수는 0이지만 저장 전 예약이 남아 있으면 자리가 돌아올 수 있다. 기억하지 않고 다음 신청도 Redis가 판단한다. */
    @Test
    void soldOutWithPendingReservationsIsNotRemembered() {
        long campaign = prepared(2);
        facade.issue(user(), campaign);
        facade.issue(user(), campaign);
        assertConflict(user(), campaign, CouponRejections.SOLD_OUT);
        assertThat(pendingReservations(campaign)).isEqualTo("2");

        long next = user();
        clearInvocations(gate);
        assertConflict(next, campaign, CouponRejections.SOLD_OUT);
        verify(gate).enqueue(eq(campaign), eq(next), any());
    }

    /** 예약이 모두 저장되면 최종 매진이다. 한 번 확인한 뒤로는 Redis에 가지 않고, 받은 사람에게도 쿠폰함 안내가 붙은 409로 답한다. */
    @Test
    void finalSoldOutIsRememberedAndSkipsRedis() {
        long campaign = prepared(2);
        long winner = user();
        facade.issue(winner, campaign);
        facade.issue(user(), campaign);
        worker.processOnce();
        assertThat(pendingReservations(campaign)).isEqualTo("0");

        assertConflict(user(), campaign, CouponRejections.SOLD_OUT);

        clearInvocations(gate);
        assertConflict(user(), campaign, CouponRejections.CLOSED_SOLD_OUT);
        assertConflict(winner, campaign, CouponRejections.CLOSED_SOLD_OUT);
        verify(gate, never()).enqueue(any(), any(), any());
    }

    /** 처리 중 예약이 취소되면 돌아온 자리를 그 뒤에 온 사람이 받는다. 남은 수 0만 보고 기억했다면 이 사람이 거절됐다. */
    @Test
    void seatReturnedByCancellationGoesToNextApplicant() {
        long campaign = prepared(2);
        facade.issue(user(), campaign);
        facade.issue(UNKNOWN_USER, campaign);
        assertConflict(user(), campaign, CouponRejections.SOLD_OUT);

        worker.processOnce();
        assertThat(remaining(campaign)).isEqualTo("1");

        assertThat(facade.issue(user(), campaign).pending()).isTrue();
    }

    /** 다시 준비하면 이 서버의 기억을 지운다. 한도를 늘려 준비한 뒤의 신청은 다시 Redis가 판단한다. */
    @Test
    void preparationForgetsRememberedSoldOut() {
        long campaign = prepared(1);
        facade.issue(user(), campaign);
        worker.processOnce();
        assertConflict(user(), campaign, CouponRejections.SOLD_OUT);
        assertConflict(user(), campaign, CouponRejections.CLOSED_SOLD_OUT);

        jdbc.update("UPDATE coupon_campaign SET total_quota = 2 WHERE id = ?", campaign);
        facade.prepareIssueGate(campaign);

        assertThat(facade.issue(user(), campaign).pending()).isTrue();
    }

    private void assertConflict(long userId, long campaign, String message) {
        assertThatThrownBy(() -> facade.issue(userId, campaign)).isInstanceOfSatisfying(CoreException.class,
            e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.CONFLICT)).hasMessage(message);
    }

    private long prepared(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("로컬 매진 캐시", "5,000원 할인권", true, quota,
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

    private String pendingReservations(long id) {
        return (String) redis.opsForHash().get(RedisCouponIssueGate.stockKey(id), "pending");
    }
}
