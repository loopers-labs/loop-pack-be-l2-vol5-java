package com.loopers.domain.coupon;

import com.loopers.domain.coupon.CouponIssueRequest.Resubmission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponIssueRequestTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final Long CAMPAIGN_ID = 1L;
    private static final Long USER_ID = 73L;
    private static final String REQUEST_ID = "8e078261-f7c6-4d41-a8e9-f068d900c0cf";
    private static final String TOKEN = "f0d3a3c2-2a51-4e0b-9d6e-1f1f2b8c4a10";

    @DisplayName("미완료 상태는 접수 한도에 포함되고, 완료 상태는 제외된다.")
    @Test
    void separatesUnfinishedAndCompletedStatuses() {
        assertThat(Stream.of(CouponIssueRequestStatus.values()).filter(CouponIssueRequestStatus::isUnfinished))
            .containsExactlyInAnyOrder(CouponIssueRequestStatus.WAITING, CouponIssueRequestStatus.PROCESSING,
                CouponIssueRequestStatus.UNKNOWN);
        assertThat(Stream.of(CouponIssueRequestStatus.values()).filter(CouponIssueRequestStatus::isCompleted))
            .containsExactlyInAnyOrder(CouponIssueRequestStatus.SUCCEEDED, CouponIssueRequestStatus.FAILED);
    }

    @DisplayName("결과 불명 사유만 UNKNOWN 상태이고, 나머지 사유는 FAILED 상태다.")
    @ParameterizedTest
    @EnumSource(CouponIssueRequestReason.class)
    void mapsEachReasonToItsStatus(CouponIssueRequestReason reason) {
        CouponIssueRequestStatus expected = reason == CouponIssueRequestReason.DB_RESULT_UNKNOWN
            ? CouponIssueRequestStatus.UNKNOWN
            : CouponIssueRequestStatus.FAILED;

        assertThat(reason.getStatus()).isEqualTo(expected);
        assertThat(reason.getMessage()).isNotBlank();
    }

    @DisplayName("시스템 실패 사유만 사용자가 다시 접수할 때 다시 대기한다.")
    @Test
    void requeuesOnlySystemFailuresOnResubmit() {
        assertThat(Stream.of(CouponIssueRequestReason.values()).filter(CouponIssueRequestReason::isRequeuedOnResubmit))
            .containsExactlyInAnyOrder(CouponIssueRequestReason.DB_ERROR, CouponIssueRequestReason.INTERNAL_ERROR);
    }

    @DisplayName("캠페인 거절 사유는 품절과 기간 밖 reasonCode로 바뀐다.")
    @Test
    void convertsCampaignRejectionReasons() {
        assertThat(CouponIssueRequestReason.from(CouponNotIssuableException.Reason.SOLD_OUT))
            .isEqualTo(CouponIssueRequestReason.SOLD_OUT);
        assertThat(CouponIssueRequestReason.from(CouponNotIssuableException.Reason.OUT_OF_PERIOD))
            .isEqualTo(CouponIssueRequestReason.CAMPAIGN_NOT_ISSUABLE);
    }

    @DisplayName("새 작업은 대기 상태로 만들고 쿠폰, 사유, 실행 token을 비운다.")
    @Test
    void createsWaitingRequest() {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, USER_ID, REQUESTED_AT);

        assertThat(request.getRequestId()).isNotBlank();
        assertThat(request.getCampaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(request.getUserId()).isEqualTo(USER_ID);
        assertThat(request.getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
        assertThat(request.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(request.getCouponId()).isNull();
        assertThat(request.getReason()).isNull();
        assertThat(request.getMessage()).isNull();
        assertThat(request.getExecutionToken()).isNull();
    }

    @DisplayName("새 작업마다 서로 다른 작업 ID를 만든다.")
    @Test
    void createsDistinctRequestIds() {
        CouponIssueRequest first = CouponIssueRequest.create(CAMPAIGN_ID, USER_ID, REQUESTED_AT);
        CouponIssueRequest second = CouponIssueRequest.create(CAMPAIGN_ID, USER_ID, REQUESTED_AT);

        assertThat(first.getRequestId()).isNotEqualTo(second.getRequestId());
    }

    @DisplayName("새 작업에는 캠페인, 사용자, 접수 시각이 필요하다.")
    @Test
    void requiresIdentityAndRequestedAtOnCreate() {
        assertThatThrownBy(() -> CouponIssueRequest.create(null, USER_ID, REQUESTED_AT))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CouponIssueRequest.create(CAMPAIGN_ID, null, REQUESTED_AT))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CouponIssueRequest.create(CAMPAIGN_ID, USER_ID, null))
            .isInstanceOf(NullPointerException.class);
    }

    @DisplayName("저장된 작업은 상태마다 허용된 쿠폰, 사유, 실행 token 조합으로만 복원한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("validStoredRequests")
    void restoresValidStoredRequest(StoredRequest stored) {
        assertThatCode(stored::restore).doesNotThrowAnyException();
    }

    private static Stream<Named<StoredRequest>> validStoredRequests() {
        return Stream.of(
            Named.of("대기", stored(CouponIssueRequestStatus.WAITING, null, null, null)),
            Named.of("처리 중", stored(CouponIssueRequestStatus.PROCESSING, null, null, TOKEN)),
            Named.of("성공", stored(CouponIssueRequestStatus.SUCCEEDED, 101L, null, TOKEN)),
            Named.of("업무 거절 실패", stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.SOLD_OUT, TOKEN)),
            Named.of("시스템 실패", stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN)),
            Named.of("결과 불명", stored(CouponIssueRequestStatus.UNKNOWN, null, CouponIssueRequestReason.DB_RESULT_UNKNOWN,
                TOKEN))
        );
    }

    @DisplayName("상태와 맞지 않는 쿠폰, 사유, 실행 token 조합은 복원하지 않는다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentStoredRequests")
    void rejectsInconsistentStoredRequest(StoredRequest stored) {
        assertThatThrownBy(stored::restore).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Named<StoredRequest>> inconsistentStoredRequests() {
        return Stream.of(
            Named.of("대기인데 실행 token 있음", stored(CouponIssueRequestStatus.WAITING, null, null, TOKEN)),
            Named.of("대기인데 사유 있음", stored(CouponIssueRequestStatus.WAITING, null, CouponIssueRequestReason.DB_ERROR, null)),
            Named.of("처리 중인데 실행 token 없음", stored(CouponIssueRequestStatus.PROCESSING, null, null, null)),
            Named.of("처리 중인데 쿠폰 있음", stored(CouponIssueRequestStatus.PROCESSING, 101L, null, TOKEN)),
            Named.of("성공인데 쿠폰 없음", stored(CouponIssueRequestStatus.SUCCEEDED, null, null, TOKEN)),
            Named.of("성공인데 사유 있음", stored(CouponIssueRequestStatus.SUCCEEDED, 101L, CouponIssueRequestReason.SOLD_OUT,
                TOKEN)),
            Named.of("실패인데 사유 없음", stored(CouponIssueRequestStatus.FAILED, null, null, TOKEN)),
            Named.of("실패인데 결과 불명 사유", stored(CouponIssueRequestStatus.FAILED, null,
                CouponIssueRequestReason.DB_RESULT_UNKNOWN, TOKEN)),
            Named.of("실패인데 쿠폰 있음", stored(CouponIssueRequestStatus.FAILED, 101L, CouponIssueRequestReason.SOLD_OUT, TOKEN)),
            Named.of("결과 불명인데 실패 사유", stored(CouponIssueRequestStatus.UNKNOWN, null, CouponIssueRequestReason.SOLD_OUT,
                TOKEN)),
            Named.of("결과 불명인데 실행 token 없음", stored(CouponIssueRequestStatus.UNKNOWN, null,
                CouponIssueRequestReason.DB_RESULT_UNKNOWN, null))
        );
    }

    @DisplayName("저장된 작업에도 작업 ID, 캠페인, 사용자, 상태, 접수 시각이 필요하다.")
    @Test
    void requiresIdentityOnRestore() {
        assertRestoreRejected(() -> CouponIssueRequest.reconstitute(" ", CAMPAIGN_ID, USER_ID,
            CouponIssueRequestStatus.WAITING, REQUESTED_AT, null, null, null));
        assertRestoreRejected(() -> CouponIssueRequest.reconstitute(REQUEST_ID, null, USER_ID,
            CouponIssueRequestStatus.WAITING, REQUESTED_AT, null, null, null));
        assertRestoreRejected(() -> CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, null,
            CouponIssueRequestStatus.WAITING, REQUESTED_AT, null, null, null));
        assertRestoreRejected(() -> CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, USER_ID,
            null, REQUESTED_AT, null, null, null));
        assertRestoreRejected(() -> CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, USER_ID,
            CouponIssueRequestStatus.WAITING, null, null, null, null));
    }

    @DisplayName("작업 소유자는 접수한 사용자뿐이다.")
    @Test
    void isOwnedOnlyByRequestingUser() {
        CouponIssueRequest request = CouponIssueRequest.create(CAMPAIGN_ID, USER_ID, REQUESTED_AT);

        assertThat(request.isOwnedBy(USER_ID)).isTrue();
        assertThat(request.isOwnedBy(USER_ID + 1)).isFalse();
        assertThat(request.isOwnedBy(null)).isFalse();
    }

    @DisplayName("다시 접수하면 미완료 작업은 진행 상태를, 성공과 업무 거절은 결과를 반환하고, 시스템 실패만 다시 대기한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("resubmissions")
    void decidesResubmission(StoredRequest stored, Resubmission expected) {
        assertThat(stored.restore().resubmission()).isEqualTo(expected);
    }

    private static Stream<Arguments> resubmissions() {
        return Stream.of(
            Arguments.of(
                Named.of("대기", stored(CouponIssueRequestStatus.WAITING, null, null, null)), Resubmission.RETURN_IN_PROGRESS),
            Arguments.of(
                Named.of("처리 중", stored(CouponIssueRequestStatus.PROCESSING, null, null, TOKEN)),
                Resubmission.RETURN_IN_PROGRESS),
            Arguments.of(
                Named.of("결과 불명", stored(CouponIssueRequestStatus.UNKNOWN, null, CouponIssueRequestReason.DB_RESULT_UNKNOWN,
                    TOKEN)), Resubmission.RETURN_IN_PROGRESS),
            Arguments.of(
                Named.of("성공", stored(CouponIssueRequestStatus.SUCCEEDED, 101L, null, TOKEN)), Resubmission.RETURN_RESULT),
            Arguments.of(
                Named.of("품절 실패", stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.SOLD_OUT, TOKEN)),
                Resubmission.RETURN_RESULT),
            Arguments.of(
                Named.of("기간 밖 실패", stored(CouponIssueRequestStatus.FAILED, null,
                    CouponIssueRequestReason.CAMPAIGN_NOT_ISSUABLE, TOKEN)), Resubmission.RETURN_RESULT),
            Arguments.of(
                Named.of("캠페인 없음 실패", stored(CouponIssueRequestStatus.FAILED, null,
                    CouponIssueRequestReason.CAMPAIGN_NOT_FOUND, TOKEN)), Resubmission.RETURN_RESULT),
            Arguments.of(
                Named.of("DB 오류 실패", stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN)),
                Resubmission.REQUEUE),
            Arguments.of(
                Named.of("내부 오류 실패", stored(CouponIssueRequestStatus.FAILED, null,
                    CouponIssueRequestReason.INTERNAL_ERROR, TOKEN)), Resubmission.REQUEUE)
        );
    }

    @DisplayName("작업 응답의 사유 문구는 reasonCode의 정해진 문구를 쓴다.")
    @Test
    void usesFixedMessageOfReason() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.SOLD_OUT, TOKEN)
            .restore();

        assertThat(failed.getMessage()).isEqualTo(CouponIssueRequestReason.SOLD_OUT.getMessage());
    }

    private static StoredRequest stored(CouponIssueRequestStatus status, Long couponId,
                                        CouponIssueRequestReason reason, String executionToken) {
        return new StoredRequest(status, couponId, reason, executionToken);
    }

    private static void assertRestoreRejected(ThrowingCallable restore) {
        assertThatThrownBy(restore).isInstanceOf(IllegalArgumentException.class);
    }

    private record StoredRequest(CouponIssueRequestStatus status, Long couponId,
                                 CouponIssueRequestReason reason, String executionToken) {

        CouponIssueRequest restore() {
            return CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, USER_ID, status, REQUESTED_AT,
                couponId, reason, executionToken);
        }
    }
}
