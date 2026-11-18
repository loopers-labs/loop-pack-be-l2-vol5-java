package com.loopers.application.coupon.facade;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.CouponIssueRequestResender;
import com.loopers.support.CouponTables;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** worker 의 확인 대기 정리: 재전송으로 확정, 안 되면 DB 기록이 있을 때만 확정, 결과를 모르면 지우지 않는다 (8주차 D-134) */
@SpringBootTest
class CouponIssuePendingConfirmTest {

    @Autowired
    private CouponIssueWorker worker;

    @MockitoSpyBean
    private CouponIssueRequestResender resender;

    @Autowired
    private CouponTables couponTables;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier(CouponRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        couponTables.truncate();
        redisCleanUp.truncateAll();
    }

    private static String key(long campaignId, String suffix) {
        return "coupon-issue:{" + campaignId + "}:" + suffix;
    }

    private long openCampaign(int totalQuota) {
        long campaignId = couponTables.insertOpenCampaign(totalQuota);
        redis.opsForHash().putAll(key(campaignId, "meta"), Map.of(
            "total-quota", String.valueOf(totalQuota), "reserve-limit", String.valueOf(totalQuota + 1), "status", "OPEN"));
        redis.opsForValue().set(key(campaignId, "epoch"), "1");
        return campaignId;
    }

    /** api 접수 Lua 1 뒤 Kafka 확인을 받지 못한 모양 */
    private void pending(long campaignId, long userId, long sequence, long acceptedAtMillis) {
        redis.opsForHash().put(key(campaignId, "users"), String.valueOf(userId), "PENDING_CONFIRM:" + sequence);
        redis.opsForZSet().add(key(campaignId, "pending-confirm"), String.valueOf(userId), acceptedAtMillis);
    }

    private Object userState(long campaignId, long userId) {
        return redis.opsForHash().get(key(campaignId, "users"), String.valueOf(userId));
    }

    private static long longAgo() {
        return System.currentTimeMillis() - 60_000;
    }

    @DisplayName("오래된 확인 대기는 같은 순번으로 재전송해 확정되고, 같은 회차에 발급까지 된다.")
    @Test
    void resendsAndConfirms() {
        // arrange
        long campaignId = openCampaign(2);
        pending(campaignId, 7, 1, longAgo());

        // act
        int issued = worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(issued).isEqualTo(1),
            () -> assertThat(String.valueOf(userState(campaignId, 7))).startsWith("ISSUED:1:"),
            () -> assertThat(redis.opsForZSet().size(key(campaignId, "pending-confirm"))).isZero()
        );
    }

    @DisplayName("예비 순번이면 RESERVE 로 확정되어 예비 목록에 들어간다.")
    @Test
    void confirmsReserve() {
        // arrange
        long campaignId = openCampaign(2);
        pending(campaignId, 9, 3, longAgo());

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(userState(campaignId, 9)).isEqualTo("RESERVE:3"),
            () -> assertThat(redis.opsForZSet().score(key(campaignId, "reserve"), "9")).isEqualTo(3.0)
        );
    }

    @DisplayName("재전송이 안 되어도 DB 접수 기록이 있으면(이미 기록 · 적재됨) 확정한다.")
    @Test
    void confirmsFromDatabase_whenResendFails() {
        // arrange
        long campaignId = openCampaign(2);
        pending(campaignId, 7, 1, longAgo());
        jdbc.update("""
            INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, created_at, updated_at)
            VALUES (?, 7, 1, 'WAITING', 1, NOW(6), NOW(6))
            """, campaignId);
        doReturn(false).when(resender).resend(any());

        // act
        worker.runOnce();

        // assert
        assertThat(String.valueOf(userState(campaignId, 7))).startsWith("ISSUED:1:");
    }

    @DisplayName("재전송이 안 되고 DB 기록도 없으면, 지우지 않고 다음 회차로 미룬다.")
    @Test
    void keepsPending_whenNothingConfirms() {
        // arrange
        long campaignId = openCampaign(2);
        pending(campaignId, 7, 1, longAgo());
        doReturn(false).when(resender).resend(any());

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(userState(campaignId, 7)).isEqualTo("PENDING_CONFIRM:1"),
            () -> assertThat(redis.opsForZSet().score(key(campaignId, "pending-confirm"), "7")).isNotNull()
        );
    }

    @DisplayName("접수한 지 얼마 안 된 확인 대기는 건드리지 않는다. api 의 첫 send 가 아직 끝나지 않았을 수 있다.")
    @Test
    void leavesFreshPendingAlone() {
        // arrange
        long campaignId = openCampaign(2);
        pending(campaignId, 7, 1, System.currentTimeMillis() + 60_000);

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> verify(resender, never()).resend(any()),
            () -> assertThat(userState(campaignId, 7)).isEqualTo("PENDING_CONFIRM:1")
        );
    }
}
