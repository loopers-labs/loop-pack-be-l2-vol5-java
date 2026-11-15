package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.user.UserJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Redis 판단을 통과한 실행이 DB 발급 TX 앞에서 자리(분산락 N=1, 세마포어 N)를 기다리는지 본다.
 * 자리를 얻은 실행만 TX에 들어가고, 나머지는 호출 스레드에서 기다린다. 못 얻고 시간이 지나면 예약을 돌리고 429다.
 * TX 안의 캠페인 조회에서 실행을 붙잡아 자리를 쥔 상태를 만든다.
 */
abstract class CouponIssueLimitTestSupport {
    @Autowired CouponFacade facade;
    @Autowired CouponCampaignJpaRepository campaigns;
    @Autowired UserJpaRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    @MockitoSpyBean CouponIssueTransaction transaction;
    @MockitoSpyBean CouponCampaignRepositoryImpl campaignRepository;

    final List<Long> campaignIds = new ArrayList<>();
    final List<Long> userIds = new ArrayList<>();
    final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        reset(transaction, campaignRepository);
        for (Long id : campaignIds) {
            jdbc.update("DELETE FROM coupon WHERE campaign_id = ?", id);
            jdbc.update("DELETE FROM coupon_campaign WHERE id = ?", id);
            // 컨텍스트가 바뀌면 테이블이 새로 만들어져 캠페인 ID가 다시 쓰인다. 자리 수가 다른 테스트의 세마포어가 남지 않게 지운다.
            redis.delete(List.of("coupon-issue:{" + id + "}:stock", "coupon-issue:{" + id + "}:users",
                "coupon-issue-lock:{" + id + "}", "coupon-issue-semaphore:{" + id + "}"));
        }
        users.deleteAllById(userIds);
    }

    /** TX 안의 캠페인 조회에서 holding만큼 붙잡고, finish가 열리면 진행시킨다. */
    void holdInsideTransaction(CountDownLatch holding, CountDownLatch finish) {
        doAnswer(invocation -> {
            holding.countDown();
            assertThat(finish.await(20, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(campaignRepository).find(any());
    }

    Future<CouponIssueResult> issueAsync(Long userId, Long campaignId) {
        return executor.submit(() -> facade.issue(userId, campaignId));
    }

    long preparedCampaign(int quota) {
        long id = campaigns.saveAndFlush(new CouponCampaignModel("행사", "5,000원 할인권", true, quota,
            Instant.now().plusSeconds(86_400))).getId();
        campaignIds.add(id);
        // 다른 테스트가 같은 캠페인 ID로 남긴 락·세마포어(자리 수가 다를 수 있다)를 쓰지 않게 먼저 지운다.
        redis.delete(List.of("coupon-issue-lock:{" + id + "}", "coupon-issue-semaphore:{" + id + "}"));
        facade.prepareIssueGate(id);
        return id;
    }

    long user() {
        long id = users.saveAndFlush(new UserModel()).getId();
        userIds.add(id);
        return id;
    }

    String remaining(long campaignId) {
        return (String) redis.opsForHash().get("coupon-issue:{" + campaignId + "}:stock", "remaining");
    }

    long couponRows(long campaignId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaignId);
    }
}
