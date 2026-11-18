package com.loopers.application.coupon.facade;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.repository.CouponIssuanceStore;
import com.loopers.domain.coupon.vo.IssueEntry;
import com.loopers.support.CouponTables;
import com.loopers.support.error.IssueRejectedException;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 발급 실패의 분류 · 재전달 · 회수 · 최종 실패 · 승격 · 품절을 실제 MySQL · Redis 로 확인한다 (8주차 D-120 ~ D-131, D-142, D-173) */
@SpringBootTest
class CouponIssueWorkerFailureTest {

    private static final String GROUP = "coupon-issue-worker";
    private static final long LATER = Long.MAX_VALUE / 2;

    @Autowired
    private CouponIssueWorker worker;

    @Autowired
    private CouponIssueStream couponIssueStream;

    @MockitoSpyBean
    private CouponIssuanceStore couponIssuanceStore;

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

    /** api 가 열고(메타 · 표식) 확정한(WAITING + Stream, RESERVE + 예비 ZSET) 모양을 만든다 */
    private long openCampaign(int totalQuota) {
        long campaignId = couponTables.insertOpenCampaign(totalQuota);
        redis.opsForHash().putAll(key(campaignId, "meta"), Map.of(
            "total-quota", String.valueOf(totalQuota), "reserve-limit", String.valueOf(totalQuota + 1), "status", "OPEN"));
        redis.opsForValue().set(key(campaignId, "epoch"), "1");
        return campaignId;
    }

    private void waiting(long campaignId, long userId, long sequence, int unclassified) {
        redis.opsForHash().put(key(campaignId, "users"), String.valueOf(userId), "WAITING:" + sequence);
        redis.opsForStream().add(key(campaignId, "stream"), Map.of(
            "userId", String.valueOf(userId), "sequence", String.valueOf(sequence), "slotNo", String.valueOf(sequence),
            "epoch", "1", "unclassified", String.valueOf(unclassified), "transient", "0"));
    }

    private void reserve(long campaignId, long userId, long sequence) {
        redis.opsForHash().put(key(campaignId, "users"), String.valueOf(userId), "RESERVE:" + sequence);
        redis.opsForZSet().add(key(campaignId, "reserve"), String.valueOf(userId), sequence);
    }

    private Object userState(long campaignId, long userId) {
        return redis.opsForHash().get(key(campaignId, "users"), String.valueOf(userId));
    }

    private String requestStatus(long campaignId, long userId) {
        return jdbc.queryForObject("SELECT status FROM coupon_issue_request WHERE campaign_id = ? AND user_id = ?",
            String.class, campaignId, userId);
    }

    /** 지연 ZSET 의 항목을 지금 되돌리고 한 회차를 돈다 */
    private int moveAndRun(long campaignId) {
        couponIssueStream.moveDue(campaignId, LATER, 100);
        return worker.runOnce();
    }

    @DisplayName("일시 오류는 안쪽에서 다시 해 보고, 그래도 안 되면 지연 ZSET 으로 놓았다가 돌아와 발급된다. 상한이 없다. (D-126, D-128, D-129)")
    @Test
    void transientErrors_areRetriedThenRedelivered() {
        // arrange
        long campaignId = openCampaign(2);
        waiting(campaignId, 7, 1, 0);
        doThrow(new QueryTimeoutException("db timeout")).doThrow(new QueryTimeoutException("db timeout"))
            .doThrow(new QueryTimeoutException("db timeout")).doCallRealMethod()
            .when(couponIssuanceStore).issue(any(), any());

        // act
        int first = worker.runOnce();
        Long delayed = redis.opsForZSet().size(key(campaignId, "delayed"));
        int second = moveAndRun(campaignId);

        // assert
        assertAll(
            () -> assertThat(first).isZero(),
            () -> assertThat(delayed).isEqualTo(1L),
            () -> assertThat(second).isEqualTo(1),
            () -> assertThat(requestStatus(campaignId, 7)).isEqualTo("ISSUED")
        );
    }

    @DisplayName("미분류 예외가 3번이면 FAILED 가 되고, 가장 앞의 예비가 그 슬롯을 물려받아 발급된다. (D-120, D-123, D-127, D-141)")
    @Test
    void unclassifiedThreeTimes_failsAndPromotesReserve() {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 0);
        reserve(campaignId, 8, 2);
        doThrow(new IllegalStateException("bug")).doThrow(new IllegalStateException("bug"))
            .doThrow(new IllegalStateException("bug")).doCallRealMethod()
            .when(couponIssuanceStore).issue(any(), any());

        // act
        worker.runOnce();
        moveAndRun(campaignId);
        moveAndRun(campaignId);
        Object promotedState = userState(campaignId, 8);
        int issued = worker.runOnce();

        // assert
        Map<String, Object> promoted = jdbc.queryForMap(
            "SELECT status, slot_no, promoted_at FROM coupon_issue_request WHERE campaign_id = ? AND user_id = 8", campaignId);
        assertAll(
            () -> assertThat(requestStatus(campaignId, 7)).isEqualTo("FAILED"),
            () -> assertThat(userState(campaignId, 7)).isEqualTo("FAILED:1"),
            () -> assertThat(promotedState).isEqualTo("WAITING:2"),
            () -> assertThat(redis.opsForValue().get(key(campaignId, "promoted"))).isEqualTo("1"),
            () -> assertThat(issued).isEqualTo(1),
            () -> assertThat(promoted).containsEntry("status", "ISSUED").containsEntry("slot_no", 1),
            () -> assertThat(promoted.get("promoted_at")).isNotNull(),
            () -> assertThat(jdbc.queryForObject("SELECT slot_no FROM coupon WHERE campaign_id = ? AND user_id = 8",
                Integer.class, campaignId)).isEqualTo(1)
        );
    }

    @DisplayName("캠페인이 닫혔으면 영구 오류라 재시도 없이 FAILED 이고 승격하지 않는다. (D-124, D-126)")
    @Test
    void closedCampaign_failsWithoutPromotion() {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 0);
        reserve(campaignId, 8, 2);
        // worker 가 캠페인 목록을 읽은 뒤 닫힌 경우: 발급 TX 가 닫힘을 보고 영구 오류를 낸다
        redis.opsForHash().put(key(campaignId, "meta"), "status", "CLOSED");
        doThrow(new IssueRejectedException("열린 캠페인이 아님")).when(couponIssuanceStore).issue(any(), any());

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(userState(campaignId, 7)).isEqualTo("FAILED:1"),
            () -> assertThat(userState(campaignId, 8)).isEqualTo("RESERVE:2"),
            () -> assertThat(redis.opsForZSet().size(key(campaignId, "reserve"))).isEqualTo(1L)
        );
    }

    @DisplayName("FAILED 로 확정하기 전에 쿠폰이 이미 있으면(commit 결과 불명) 발급으로 끝내고 승격하지 않는다. (D-120)")
    @Test
    void finishesAsIssued_whenCouponAlreadyExistsBeforeFailing() {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 2);
        reserve(campaignId, 8, 2);
        jdbc.update("""
            INSERT INTO coupon (campaign_id, user_id, slot_no, discount_type, discount_value, description, issued_at, created_at, updated_at)
            VALUES (?, 7, 1, 'FIXED_AMOUNT', 5000, '설명', NOW(6), NOW(6), NOW(6))
            """, campaignId);
        long couponId = jdbc.queryForObject("SELECT id FROM coupon WHERE user_id = 7", Long.class);
        doThrow(new IllegalStateException("commit 결과 불명")).when(couponIssuanceStore).issue(any(), any());

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(userState(campaignId, 7)).isEqualTo("ISSUED:1:" + couponId),
            () -> assertThat(userState(campaignId, 8)).isEqualTo("RESERVE:2")
        );
    }

    @DisplayName("전달 횟수 상한을 넘은 poison 항목은 FAILED 로 두고 예비를 승격한다. (D-173)")
    @Test
    void poisonEntry_failsAndPromotes() {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 0);
        reserve(campaignId, 8, 2);
        IssueEntry read = couponIssueStream.read(campaignId, "test", 1).get(0);
        IssueEntry poison = new IssueEntry(campaignId, read.entryId(), 7, 1, 1, "1", 0, 0, 11);

        // act
        boolean issued = worker.handle(poison);

        // assert
        assertAll(
            () -> assertThat(issued).isFalse(),
            () -> assertThat(userState(campaignId, 7)).isEqualTo("FAILED:1"),
            () -> assertThat(userState(campaignId, 8)).isEqualTo("WAITING:2")
        );
    }

    @DisplayName("죽은 worker 가 가져가고 처리하지 못한 항목은 다른 worker 가 회수해 발급한다. (D-119)")
    @Test
    void reclaimsEntryOfDeadWorker() throws InterruptedException {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 0);
        couponIssueStream.read(campaignId, "dead-worker", 1);
        Thread.sleep(300);

        // act
        int issued = worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(issued).isEqualTo(1),
            () -> assertThat(redis.opsForStream().pending(key(campaignId, "stream"), GROUP).getTotalPendingMessages()).isZero()
        );
    }

    @DisplayName("발급 수가 한도에 닿으면 issuedCount 를 맞추고 품절 플래그를 세운다. (D-142)")
    @Test
    void recountsAndMarksSoldOut_whenQuotaReached() {
        // arrange
        long campaignId = openCampaign(1);
        waiting(campaignId, 7, 1, 0);

        // act
        worker.runOnce();

        // assert
        assertAll(
            () -> assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId)).isEqualTo(1),
            () -> assertThat(redis.opsForValue().get(key(campaignId, "sold-out"))).isEqualTo("1")
        );
    }
}
