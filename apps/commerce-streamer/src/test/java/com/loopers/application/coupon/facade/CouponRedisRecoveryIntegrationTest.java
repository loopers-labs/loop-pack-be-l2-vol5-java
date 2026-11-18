package com.loopers.application.coupon.facade;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.CouponIssueRequestLog;
import com.loopers.support.CouponTables;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doReturn;

/** Redis 유실 뒤 DB 에서 다시 만들기와 부분 유실 대사를 실제 MySQL · Redis 로 확인한다 (8주차 D-139, D-140, D-174, D-175) */
@SpringBootTest
class CouponRedisRecoveryIntegrationTest {

    @Autowired
    private CouponRedisRecovery recovery;

    @Autowired
    private CouponIssueWorker worker;

    @MockitoSpyBean
    private CouponIssueRequestLog requestLog;

    @Autowired
    private CouponTables couponTables;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier(CouponRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeEach
    void setUp() {
        doReturn(true).when(requestLog).caughtUp();
    }

    @AfterEach
    void tearDown() {
        couponTables.truncate();
        redisCleanUp.truncateAll();
    }

    private static String key(long campaignId, String suffix) {
        return "coupon-issue:{" + campaignId + "}:" + suffix;
    }

    private void request(long campaignId, long userId, long sequence, String status, Integer slotNo) {
        jdbc.update("""
            INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
            """, campaignId, userId, sequence, status, slotNo);
    }

    private long issuedCoupon(long campaignId, long userId, long sequence, int slotNo) {
        jdbc.update("""
            INSERT INTO coupon (campaign_id, user_id, slot_no, discount_type, discount_value, description, issued_at, created_at, updated_at)
            VALUES (?, ?, ?, 'FIXED_AMOUNT', 5000, '설명', NOW(6), NOW(6), NOW(6))
            """, campaignId, userId, slotNo);
        long couponId = jdbc.queryForObject("SELECT id FROM coupon WHERE campaign_id = ? AND user_id = ?", Long.class, campaignId, userId);
        jdbc.update("""
            INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, coupon_id, created_at, updated_at)
            VALUES (?, ?, ?, 'ISSUED', ?, ?, NOW(6), NOW(6))
            """, campaignId, userId, sequence, slotNo, couponId);
        return couponId;
    }

    private Object userState(long campaignId, long userId) {
        return redis.opsForHash().get(key(campaignId, "users"), String.valueOf(userId));
    }

    @DisplayName("표식이 없으면 DB 에서 다시 만든다. 주인 없는 슬롯은 가장 앞 예비가 받고, 표식은 쓰지 않아 닫힌 채다. (D-174)")
    @Test
    void rebuildsFromDatabase_andStaysClosed() {
        // arrange: Redis 를 통째로 잃은 상태
        long campaignId = couponTables.insertOpenCampaign(2);
        long couponId = issuedCoupon(campaignId, 7, 1, 1);
        request(campaignId, 8, 2, "FAILED", 2);
        request(campaignId, 9, 3, "RESERVE", null);
        request(campaignId, 10, 4, "RESERVE", null);

        // act
        recovery.runOnce();

        // assert
        var entries = redis.opsForStream().range(key(campaignId, "stream"), Range.unbounded());
        assertAll(
            () -> assertThat(userState(campaignId, 7)).isEqualTo("ISSUED:1:" + couponId),
            () -> assertThat(userState(campaignId, 8)).isEqualTo("FAILED:2"),
            () -> assertThat(userState(campaignId, 9)).isEqualTo("WAITING:3"),
            () -> assertThat(userState(campaignId, 10)).isEqualTo("RESERVE:4"),
            () -> assertThat(entries).hasSize(1),
            () -> assertThat(entries.get(0).getValue()).containsEntry("userId", "9").containsEntry("slotNo", "2"),
            () -> assertThat(redis.opsForValue().get(key(campaignId, "sequence"))).isEqualTo("4"),
            () -> assertThat(redis.opsForValue().get(key(campaignId, "promoted"))).isEqualTo("1"),
            () -> assertThat(redis.hasKey(key(campaignId, "epoch"))).isFalse()
        );
    }

    @DisplayName("운영자가 다시 열면(표식) worker 가 재구성된 대기열을 이어서 발급한다. (D-166, D-174)")
    @Test
    void workerContinues_afterOperatorReopens() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        issuedCoupon(campaignId, 7, 1, 1);
        request(campaignId, 8, 2, "FAILED", 2);
        request(campaignId, 9, 3, "RESERVE", null);
        recovery.runOnce();
        redis.opsForHash().putAll(key(campaignId, "meta"), Map.of("total-quota", "2", "reserve-limit", "3", "status", "OPEN"));
        redis.opsForValue().set(key(campaignId, "epoch"), "2");

        // act
        int issued = worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(issued).isEqualTo(1),
            () -> assertThat(jdbc.queryForObject("SELECT slot_no FROM coupon WHERE campaign_id = ? AND user_id = 9",
                Integer.class, campaignId)).isEqualTo(2)
        );
    }

    @DisplayName("접수 기록 적재가 끝나지 않았으면 재구성을 미룬다. (D-140)")
    @Test
    void waits_whenConsumerHasNotCaughtUp() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        request(campaignId, 8, 1, "WAITING", 1);
        doReturn(false).when(requestLog).caughtUp();

        // act
        boolean rebuilt = recovery.rebuild(campaignId);

        // assert
        assertAll(
            () -> assertThat(rebuilt).isFalse(),
            () -> assertThat(redis.hasKey(key(campaignId, "users"))).isFalse()
        );
    }

    @DisplayName("다른 인스턴스가 재구성 잠금을 쥐고 있으면 건너뛴다. (D-174)")
    @Test
    void skips_whenAnotherInstanceHoldsLock() throws Exception {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        request(campaignId, 8, 1, "WAITING", 1);

        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                lock.setString(1, "coupon-rebuild-" + campaignId);
                lock.executeQuery().close();
            }

            // act
            boolean rebuilt = recovery.rebuild(campaignId);

            // assert
            assertAll(
                () -> assertThat(rebuilt).isFalse(),
                () -> assertThat(redis.hasKey(key(campaignId, "users"))).isFalse()
            );
            try (PreparedStatement release = other.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                release.setString(1, "coupon-rebuild-" + campaignId);
                release.executeQuery().close();
            }
        }
    }

    @DisplayName("DB 에 있는데 Redis 사용자 기록이 없으면 부분 유실로 보고 표식을 지워 닫는다. 다음 회차에 다시 만든다. (D-175)")
    @Test
    void detectsPartialLoss_thenRebuilds() {
        // arrange: 표식은 살아 있는데 user 8 기록만 사라짐
        long campaignId = couponTables.insertOpenCampaign(2);
        request(campaignId, 7, 1, "WAITING", 1);
        request(campaignId, 8, 2, "WAITING", 2);
        redis.opsForValue().set(key(campaignId, "epoch"), "1");
        redis.opsForHash().put(key(campaignId, "users"), "7", "WAITING:1");

        // act
        boolean lost = recovery.audit(campaignId);
        recovery.runOnce();

        // assert
        assertAll(
            () -> assertThat(lost).isTrue(),
            () -> assertThat(userState(campaignId, 8)).isEqualTo("WAITING:2"),
            () -> assertThat(redis.hasKey(key(campaignId, "epoch"))).isFalse()
        );
    }

    @DisplayName("Redis 에만 있는 기록(적재 지연)은 유실이 아니다.")
    @Test
    void redisOnlyRecordIsNotLoss() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        request(campaignId, 7, 1, "WAITING", 1);
        redis.opsForValue().set(key(campaignId, "epoch"), "1");
        redis.opsForHash().put(key(campaignId, "users"), "7", "WAITING:1");
        redis.opsForHash().put(key(campaignId, "users"), "8", "PENDING_CONFIRM:2");

        // act & assert
        assertAll(
            () -> assertThat(recovery.audit(campaignId)).isFalse(),
            () -> assertThat(redis.hasKey(key(campaignId, "epoch"))).isTrue()
        );
    }
}
