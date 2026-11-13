package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영자가 redis-cli --eval로 실행하는 수동 복구 Lua를 같은 파일로 실행해 검증한다.
 */
@SpringBootTest
class RedisCouponIssueQueueRecoverScriptIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 9007L;
    private static final String OTHER_TOKEN = "token-of-another-execution";

    @Autowired private CouponIssueQueue queue;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUpKeys() {
        redisTemplate.delete(List.of(RedisCouponIssuePermits.PERMITS_KEY, RedisCouponIssueQueue.WAITING_KEY,
            RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }

    @DisplayName("처리 중 작업이 실제로 발급됐으면 성공으로 바꾸고 처리 중 List에서 빼며 자기 token만 반환한다.")
    @Test
    void recoversProcessingRequestAsSucceeded() {
        CouponIssueRequest request = processing(1101L, "token-1101");
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, OTHER_TOKEN);

        List<?> result = recover(request.getRequestId(), "PROCESSING", "token-1101", "SUCCEED", "3001", "");

        assertThat(result.get(0)).isEqualTo("APPLIED");
        assertThat(result.get(2)).isEqualTo("1");
        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(stored.getCouponId()).isEqualTo(3001L);
        });
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).isEmpty();
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY)).containsExactly(OTHER_TOKEN);
    }

    @DisplayName("결과 불명 작업이 발급되지 않았고 발급할 수 있으면 같은 ID로 다시 대기시키고 token을 비운다.")
    @Test
    void requeuesUnknownRequest() {
        CouponIssueRequest request = processing(1102L, "token-1102");
        queue.recordUnknown(request.getRequestId(), "token-1102");

        List<?> result = recover(request.getRequestId(), "UNKNOWN", "token-1102", "REQUEUE", "", "");

        assertThat(result.get(0)).isEqualTo("APPLIED");
        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
            assertThat(stored.getExecutionToken()).isNull();
            assertThat(stored.getReason()).isNull();
        });
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(request.getRequestId());
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).isEmpty();
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    @DisplayName("처리 중 작업이 발급되지 않았고 발급할 수 없으면 사유를 기록해 실패로 바꾼다.")
    @Test
    void recoversProcessingRequestAsFailed() {
        CouponIssueRequest request = processing(1103L, "token-1103");

        List<?> result = recover(request.getRequestId(), "PROCESSING", "token-1103", "FAIL", "", "SOLD_OUT");

        assertThat(result.get(0)).isEqualTo("APPLIED");
        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.FAILED);
            assertThat(stored.getReason()).isEqualTo(CouponIssueRequestReason.SOLD_OUT);
        });
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).isEmpty();
    }

    @DisplayName("처리 중 List가 [A, B, C]일 때 B만 복구하면 A와 C는 그대로 남는다.")
    @Test
    void recoversOnlyMiddleRequest() {
        CouponIssueRequest first = processing(1111L, "token-a");
        CouponIssueRequest middle = processing(1112L, "token-b");
        CouponIssueRequest last = processing(1113L, "token-c");

        recover(middle.getRequestId(), "PROCESSING", "token-b", "REQUEUE", "", "");

        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY))
            .containsExactly(first.getRequestId(), last.getRequestId());
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(middle.getRequestId());
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
            .containsExactlyInAnyOrder("token-a", "token-c");
    }

    @DisplayName("조회 뒤 상태나 token이 바뀌었으면 아무것도 바꾸지 않고 현재 작업을 돌려준다.")
    @Test
    void returnsMismatchWhenStateOrTokenChanged() {
        CouponIssueRequest request = processing(1121L, "token-1121");

        assertThat(recover(request.getRequestId(), "UNKNOWN", "token-1121", "REQUEUE", "", "").get(0))
            .isEqualTo("MISMATCH");
        assertThat(recover(request.getRequestId(), "PROCESSING", "token-stale", "REQUEUE", "", "").get(0))
            .isEqualTo("MISMATCH");

        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).containsExactly(request.getRequestId());
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
            .containsExactly("token-1121");
    }

    @DisplayName("같은 복구를 다시 실행하면 두 번째는 상태가 달라 바꾸지 않고 List 항목도 늘지 않는다.")
    @Test
    void keepsResultWhenRecoveryIsRepeated() {
        CouponIssueRequest request = processing(1131L, "token-1131");
        recover(request.getRequestId(), "PROCESSING", "token-1131", "REQUEUE", "", "");

        assertThat(recover(request.getRequestId(), "PROCESSING", "token-1131", "REQUEUE", "", "").get(0))
            .isEqualTo("MISMATCH");
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(request.getRequestId());
    }

    @DisplayName("완료된 작업에는 자기 잔여 permit만 정리하고, 다른 token으로는 정리하지 않는다.")
    @Test
    void releasesOnlyOwnResidualPermitOfCompletedRequest() {
        CouponIssueRequest request = processing(1141L, "token-1141");
        queue.recordSucceeded(request.getRequestId(), "token-1141", 3002L);
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, OTHER_TOKEN);

        assertThat(recover(request.getRequestId(), "SUCCEEDED", OTHER_TOKEN, "RELEASE", "", "").get(0))
            .isEqualTo("MISMATCH");
        List<?> released = recover(request.getRequestId(), "SUCCEEDED", "token-1141", "RELEASE", "", "");

        assertThat(released.get(0)).isEqualTo("APPLIED");
        assertThat(released.get(2)).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY)).containsExactly(OTHER_TOKEN);
        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(stored.getCouponId()).isEqualTo(3002L);
        });
    }

    @DisplayName("대기 작업, 잘못된 입력, 어긋난 저장 상태는 오류로 거절하고 아무것도 바꾸지 않는다.")
    @Test
    void rejectsWaitingRequestInvalidInputOrInconsistentState() {
        // 가져오기는 대기 List 맨 앞을 가져가므로 처리 중 작업을 먼저 만든 뒤 대기 작업을 접수한다.
        CouponIssueRequest request = processing(1152L, "token-1152");
        CouponIssueRequest waiting = CouponIssueRequest.create(CAMPAIGN_ID, 1151L, REQUESTED_AT);
        queue.submit(waiting, 20);
        assertRejected(() -> recover(waiting.getRequestId(), "WAITING", "token-none", "REQUEUE", "", ""));

        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1152", "SUCCEED", "", ""));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1152", "FAIL", "",
            "DB_RESULT_UNKNOWN"));
        assertRejected(() -> recover("missing-request-id", "PROCESSING", "token-1152", "REQUEUE", "", ""));

        redisTemplate.opsForList().remove(RedisCouponIssueQueue.PROCESSING_KEY, 1, request.getRequestId());
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1152", "REQUEUE", "", ""));

        assertThat(queue.findById(request.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING));
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
            .containsExactly("token-1152");
        assertThat(listIds(RedisCouponIssueQueue.WAITING_KEY)).containsExactly(waiting.getRequestId());
    }

    @DisplayName("Java가 읽을 수 없는 사유나 couponId를 넣으면 거절하고 작업과 token을 그대로 둔다.")
    @Test
    void rejectsReasonOrCouponIdThatJavaCannotRead() {
        CouponIssueRequest request = processing(1161L, "token-1161");

        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "FAIL", "", "SOLDOUT"));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "FAIL", "", "WAITING"));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "SUCCEED", "3001x", ""));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "SUCCEED", "1e3", ""));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "SUCCEED",
            "9223372036854775808", ""));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "SUCCEED", "0", ""));
        assertRejected(() -> recover(request.getRequestId(), "PROCESSING", "token-1161", "SUCCEED", "0123", ""));

        assertThat(queue.findById(request.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING));
        assertThat(listIds(RedisCouponIssueQueue.PROCESSING_KEY)).containsExactly(request.getRequestId());
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
            .containsExactly("token-1161");
    }

    @DisplayName("couponId는 Java Long 최댓값까지 받고, 기록한 작업을 앱이 다시 읽을 수 있다.")
    @Test
    void acceptsCouponIdUpToLongMaxValue() {
        CouponIssueRequest request = processing(1162L, "token-1162");

        recover(request.getRequestId(), "PROCESSING", "token-1162", "SUCCEED", String.valueOf(Long.MAX_VALUE), "");

        assertThat(queue.findById(request.getRequestId())).hasValueSatisfying(stored -> {
            assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(stored.getCouponId()).isEqualTo(Long.MAX_VALUE);
        });
    }

    /**
     * 작업을 접수하고 가져가며, 그 실행의 permit token을 Set에 남긴다.
     */
    private CouponIssueRequest processing(Long userId, String token) {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, userId, REQUESTED_AT);
        queue.submit(request, 20);
        queue.claim(token);
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, token);
        return request;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> recover(String requestId, String expectedStatus, String expectedToken, String action,
                            String couponId, String reasonCode) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-queue-recover.lua"));
        script.setResultType(List.class);
        return redisTemplate.execute(script, List.of(RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.WAITING_KEY,
                RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssuePermits.PERMITS_KEY),
            requestId, expectedStatus, expectedToken, action, couponId, reasonCode);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable recovery) {
        assertThatThrownBy(recovery).isInstanceOf(DataAccessException.class).hasStackTraceContaining("coupon queue:");
    }

    private List<String> listIds(String key) {
        return redisTemplate.opsForList().range(key, 0, -1);
    }
}
