package com.loopers.application.coupon.facade;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.vo.IssueEntry;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 발급 worker 의 정상 경로와 멱등을 실제 MySQL · Redis 로 확인한다 (8주차 D-119, D-125, D-141) */
@SpringBootTest
class CouponIssueWorkerIntegrationTest {

    private static final String GROUP = "coupon-issue-worker";

    @Autowired
    private CouponIssueWorker worker;

    @Autowired
    private CouponIssueSteps couponIssueSteps;

    @Autowired
    private CouponIssueStream couponIssueStream;

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

    /** api 의 열기 · 접수 Lua 2 가 남기는 모양을 그대로 만든다 */
    private void queued(long campaignId, long userId, long sequence) {
        redis.opsForValue().setIfAbsent(key(campaignId, "epoch"), "1");
        redis.opsForHash().put(key(campaignId, "users"), String.valueOf(userId), "WAITING:" + sequence);
        redis.opsForStream().add(key(campaignId, "stream"), Map.of(
            "userId", String.valueOf(userId), "sequence", String.valueOf(sequence),
            "slotNo", String.valueOf(sequence), "epoch", "1"));
    }

    private long pendingOf(long campaignId) {
        return redis.opsForStream().pending(key(campaignId, "stream"), GROUP).getTotalPendingMessages();
    }

    private long couponCount(long campaignId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaignId);
    }

    @DisplayName("대기열 항목을 꺼내 슬롯에 발급하고, 접수 기록 · Redis 를 ISSUED 로 바꾼 뒤 XACK 한다. (D-125, D-141)")
    @Test
    void issuesThenUpdatesRedisAndAcks() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        jdbc.update("""
            INSERT INTO coupon_issue_request (campaign_id, user_id, sequence, status, slot_no, created_at, updated_at)
            VALUES (?, 7, 1, 'WAITING', 1, NOW(6), NOW(6))
            """, campaignId);
        queued(campaignId, 7, 1);

        // act
        int completed = worker.runOnce();

        // assert
        Map<String, Object> coupon = jdbc.queryForMap("SELECT id, slot_no, description FROM coupon WHERE campaign_id = ? AND user_id = 7", campaignId);
        Map<String, Object> request = jdbc.queryForMap("SELECT status, coupon_id FROM coupon_issue_request WHERE campaign_id = ? AND user_id = 7", campaignId);
        long couponId = ((Number) coupon.get("id")).longValue();
        assertAll(
            () -> assertThat(completed).isEqualTo(1),
            () -> assertThat(coupon).containsEntry("slot_no", 1).containsEntry("description", "5,000원 할인권"),
            () -> assertThat(request).containsEntry("status", "ISSUED"),
            () -> assertThat(((Number) request.get("coupon_id")).longValue()).isEqualTo(couponId),
            () -> assertThat(redis.opsForHash().get(key(campaignId, "users"), "7")).isEqualTo("ISSUED:1:" + couponId),
            () -> assertThat(pendingOf(campaignId)).isZero()
        );
    }

    @DisplayName("접수 기록이 아직 적재되지 않았으면, 발급 TX 가 ISSUED 행을 만든다. (D-132)")
    @Test
    void createsRequestRow_whenConsumerHasNotLoadedYet() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        queued(campaignId, 7, 1);

        // act
        worker.runOnce();

        // assert
        assertThat(jdbc.queryForObject("SELECT status FROM coupon_issue_request WHERE campaign_id = ? AND user_id = 7",
            String.class, campaignId)).isEqualTo("ISSUED");
    }

    @DisplayName("같은 항목을 다시 처리하면(commit 뒤 Redis 갱신 전 중단) 쿠폰은 한 장이고 같은 couponId 다. (D-125)")
    @Test
    void isIdempotent_whenSameEntryProcessedAgain() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        IssueEntry entry = new IssueEntry(campaignId, "0-1", 7, 1, 1, "1");

        // act
        long first = couponIssueSteps.issue(entry);
        long second = couponIssueSteps.issue(entry);

        // assert
        assertAll(
            () -> assertThat(second).isEqualTo(first),
            () -> assertThat(couponCount(campaignId)).isEqualTo(1L)
        );
    }

    @DisplayName("Redis 갱신은 끝 상태(ISSUED · FAILED)를 덮지 않는다. (D-126)")
    @Test
    void doesNotOverwriteTerminalState() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        queued(campaignId, 7, 1);
        redis.opsForHash().put(key(campaignId, "users"), "7", "FAILED:1");
        IssueEntry entry = couponIssueStream.read(campaignId, "test", 1).get(0);

        // act
        couponIssueStream.completeIssued(entry, 900L);

        // assert
        assertAll(
            () -> assertThat(redis.opsForHash().get(key(campaignId, "users"), "7")).isEqualTo("FAILED:1"),
            () -> assertThat(pendingOf(campaignId)).isZero()
        );
    }

    @DisplayName("열리지 않은 캠페인이거나 슬롯이 한도 밖이면 발급하지 않고 대기열에 남긴다.")
    @Test
    void leavesPending_whenIssueFails() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(1);
        queued(campaignId, 7, 2);

        // act
        int completed = worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(completed).isZero(),
            () -> assertThat(couponCount(campaignId)).isZero(),
            () -> assertThat(pendingOf(campaignId)).isEqualTo(1L)
        );
    }

    @DisplayName("표식이 없으면(Redis 상태 유실) 대기열을 읽지 않는다. (D-139)")
    @Test
    void readsNothing_whenEpochMissing() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(2);
        queued(campaignId, 7, 1);
        redis.delete(key(campaignId, "epoch"));

        // act & assert
        assertAll(
            () -> assertThat(couponIssueStream.read(campaignId, "test", 10)).isEqualTo(List.of()),
            () -> assertThat(worker.runOnce()).isZero()
        );
    }
}
