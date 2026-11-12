package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueClaim.Claimed;
import com.loopers.application.coupon.CouponIssueQueueRecord;
import com.loopers.application.coupon.CouponIssueQueueRecord.AlreadyApplied;
import com.loopers.application.coupon.CouponIssueQueueRecord.Applied;
import com.loopers.application.coupon.CouponIssueQueueRecord.Rejected;
import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RedisCouponIssueQueueRecordIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 9003L;

    @Autowired private CouponIssueQueue queue;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanUpQueueKeys() {
        redisTemplate.delete(List.of(RedisCouponIssueQueue.WAITING_KEY, RedisCouponIssueQueue.PROCESSING_KEY,
            RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }

    @DisplayName("성공을 기록하면 쿠폰과 실행 token을 남기고 처리 중 List에서만 지운다.")
    @Test
    void recordsSuccessAndRemovesFromProcessing() {
        CouponIssueRequest request = submitAndClaim(801L, "token-801");

        CouponIssueQueueRecord result = queue.recordSucceeded(request.getRequestId(), "token-801", 1001L);

        assertThat(result).isInstanceOfSatisfying(Applied.class, applied -> {
            assertThat(applied.request().getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(applied.request().getCouponId()).isEqualTo(1001L);
            assertThat(applied.request().getExecutionToken()).isEqualTo("token-801");
        });
        assertThat(processingIds()).isEmpty();
        assertThat(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, 801L))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED));
    }

    @DisplayName("실패를 기록하면 사유를 남기고 처리 중 List에서만 지운다.")
    @Test
    void recordsFailureAndRemovesFromProcessing() {
        CouponIssueRequest request = submitAndClaim(802L, "token-802");

        CouponIssueQueueRecord result = queue.recordFailed(request.getRequestId(), "token-802",
            CouponIssueRequestReason.SOLD_OUT);

        assertThat(result).isInstanceOfSatisfying(Applied.class, applied -> {
            assertThat(applied.request().getStatus()).isEqualTo(CouponIssueRequestStatus.FAILED);
            assertThat(applied.request().getReason()).isEqualTo(CouponIssueRequestReason.SOLD_OUT);
            assertThat(applied.request().getExecutionToken()).isEqualTo("token-802");
        });
        assertThat(processingIds()).isEmpty();
    }

    @DisplayName("결과 불명을 기록하면 처리 중 List에 남겨 미완료 한도를 계속 차지한다.")
    @Test
    void recordsUnknownAndKeepsProcessingEntry() {
        CouponIssueRequest request = submitAndClaim(803L, "token-803");

        CouponIssueQueueRecord result = queue.recordUnknown(request.getRequestId(), "token-803");

        assertThat(result).isInstanceOfSatisfying(Applied.class, applied -> {
            assertThat(applied.request().getStatus()).isEqualTo(CouponIssueRequestStatus.UNKNOWN);
            assertThat(applied.request().getReason()).isEqualTo(CouponIssueRequestReason.DB_RESULT_UNKNOWN);
        });
        assertThat(processingIds()).containsExactly(request.getRequestId());
    }

    @DisplayName("처리 중 List가 [A, B, C]일 때 B만 완료하면 A와 C는 순서대로 남는다.")
    @Test
    void removesOnlyRecordedRequestFromMiddleOfProcessing() {
        CouponIssueRequest first = submitAndClaim(811L, "token-a");
        CouponIssueRequest middle = submitAndClaim(812L, "token-b");
        CouponIssueRequest last = submitAndClaim(813L, "token-c");
        assertThat(processingIds()).containsExactly(first.getRequestId(), middle.getRequestId(), last.getRequestId());

        queue.recordSucceeded(middle.getRequestId(), "token-b", 1002L);

        assertThat(processingIds()).containsExactly(first.getRequestId(), last.getRequestId());
    }

    @DisplayName("다른 실행의 token으로 기록하면 거절하고 작업을 바꾸지 않는다.")
    @Test
    void rejectsRecordWithOtherExecutionToken() {
        CouponIssueRequest request = submitAndClaim(820L, "token-current");

        CouponIssueQueueRecord result = queue.recordSucceeded(request.getRequestId(), "token-stale", 1003L);

        assertThat(result).isInstanceOfSatisfying(Rejected.class, rejected -> {
            assertThat(rejected.request().getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING);
            assertThat(rejected.request().getExecutionToken()).isEqualTo("token-current");
        });
        assertThat(processingIds()).containsExactly(request.getRequestId());
    }

    @DisplayName("같은 실행이 같은 결과를 다시 기록하면 이미 기록됐다고 돌려주고 결과를 유지한다.")
    @Test
    void returnsAlreadyAppliedForRepeatedSameRecord() {
        CouponIssueRequest request = submitAndClaim(830L, "token-830");
        queue.recordSucceeded(request.getRequestId(), "token-830", 1004L);

        CouponIssueQueueRecord again = queue.recordSucceeded(request.getRequestId(), "token-830", 1004L);

        assertThat(again).isInstanceOfSatisfying(AlreadyApplied.class,
            already -> assertThat(already.request().getCouponId()).isEqualTo(1004L));
        assertThat(processingIds()).isEmpty();
    }

    @DisplayName("결과 불명을 같은 실행이 다시 기록하면 이미 기록됐다고 돌려준다.")
    @Test
    void returnsAlreadyAppliedForRepeatedUnknownRecord() {
        CouponIssueRequest request = submitAndClaim(831L, "token-831");
        queue.recordUnknown(request.getRequestId(), "token-831");

        assertThat(queue.recordUnknown(request.getRequestId(), "token-831")).isInstanceOf(AlreadyApplied.class);
        assertThat(processingIds()).containsExactly(request.getRequestId());
    }

    @DisplayName("이미 성공한 작업에 다른 결과를 기록하면 거절하고 성공 결과를 유지한다.")
    @Test
    void rejectsDifferentResultAfterCompletion() {
        CouponIssueRequest request = submitAndClaim(840L, "token-840");
        queue.recordSucceeded(request.getRequestId(), "token-840", 1005L);

        CouponIssueQueueRecord result = queue.recordFailed(request.getRequestId(), "token-840",
            CouponIssueRequestReason.DB_ERROR);

        assertThat(result).isInstanceOfSatisfying(Rejected.class, rejected -> {
            assertThat(rejected.request().getStatus()).isEqualTo(CouponIssueRequestStatus.SUCCEEDED);
            assertThat(rejected.request().getCouponId()).isEqualTo(1005L);
        });
    }

    @DisplayName("대기 중인 작업에 결과를 기록하면 거절한다.")
    @Test
    void rejectsRecordForWaitingRequest() {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, 850L, REQUESTED_AT);
        queue.submit(request, 20);

        assertThat(queue.recordSucceeded(request.getRequestId(), "token-850", 1006L)).isInstanceOf(Rejected.class);
        assertThat(waitingIds()).containsExactly(request.getRequestId());
    }

    @DisplayName("처리 중 작업이 처리 중 List에 없으면 저장 불일치로 멈추고 작업을 바꾸지 않는다.")
    @Test
    void rejectsProcessingRequestMissingFromProcessingList() {
        CouponIssueRequest request = submitAndClaim(860L, "token-860");
        redisTemplate.delete(RedisCouponIssueQueue.PROCESSING_KEY);

        assertThatThrownBy(() -> queue.recordSucceeded(request.getRequestId(), "token-860", 1007L))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(queue.findById(request.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING));
    }

    @DisplayName("작업 정보가 없거나 기록 입력이 잘못되면 스크립트 오류로 거절한다.")
    @Test
    void rejectsMissingJobOrInvalidInput() {
        CouponIssueRequest request = submitAndClaim(870L, "token-870");

        assertThatThrownBy(() -> queue.recordSucceeded("missing-job-id", "token-870", 1008L))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThatThrownBy(() -> queue.recordSucceeded(request.getRequestId(), "token-870", null))
            .isInstanceOf(CouponIssueQueueScriptException.class);
        assertThatThrownBy(() -> queue.recordFailed(request.getRequestId(), "token-870",
            CouponIssueRequestReason.DB_RESULT_UNKNOWN)).isInstanceOf(CouponIssueQueueScriptException.class);
        assertThat(queue.findById(request.getRequestId()))
            .hasValueSatisfying(stored -> assertThat(stored.getStatus()).isEqualTo(CouponIssueRequestStatus.PROCESSING));
        assertThat(processingIds()).containsExactly(request.getRequestId());
    }

    private CouponIssueRequest submitAndClaim(Long userId, String token) {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, userId, REQUESTED_AT);
        queue.submit(request, 20);
        assertThat(queue.claim(token)).isInstanceOfSatisfying(Claimed.class,
            claimed -> assertThat(claimed.request().getRequestId()).isEqualTo(request.getRequestId()));
        return request;
    }

    private List<String> waitingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.WAITING_KEY, 0, -1);
    }

    private List<String> processingIds() {
        return redisTemplate.opsForList().range(RedisCouponIssueQueue.PROCESSING_KEY, 0, -1);
    }
}
