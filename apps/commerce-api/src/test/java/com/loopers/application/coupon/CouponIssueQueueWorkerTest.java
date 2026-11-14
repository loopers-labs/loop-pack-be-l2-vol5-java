package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueExecution.AlreadyIssued;
import com.loopers.application.coupon.CouponIssueExecution.Failed;
import com.loopers.application.coupon.CouponIssueExecution.Issued;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponNotIssuableException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueQueueWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Long CAMPAIGN_ID = 41L;
    private static final Long USER_ID = 73L;
    private static final String REQUEST_ID = "8e078261-f7c6-4d41-a8e9-f068d900c0cf";
    private static final CouponIssueInfo ISSUED = new CouponIssueInfo(101L, CAMPAIGN_ID, NOW, "5,000원 할인권");

    @Mock private CouponIssueQueue queue;
    @Mock private CouponIssuePermits permits;
    @Mock private CouponIssueExecutor executor;
    @Mock private CouponRepository coupons;

    private CouponIssueAdmissionState admission;
    private CouponIssueQueueWorker worker;

    @BeforeEach
    void setUp() {
        admission = new CouponIssueAdmissionState();
        admission.open();
        worker = new CouponIssueQueueWorker(queue, permits, executor, coupons, admission,
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(false)));
    }

    @DisplayName("신규 수용이 닫혀 있으면 permit을 요청하지 않는다.")
    @Test
    void doesNothingWhenAdmissionIsClosed() {
        admission.close();

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.ADMISSION_CLOSED);
        verifyNoInteractions(queue, permits, executor, coupons);
    }

    @DisplayName("대기 작업이 없으면 permit을 요청하지 않는다.")
    @Test
    void doesNothingWhenNothingIsWaiting() {
        when(queue.hasWaiting()).thenReturn(false);

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.IDLE);
        verifyNoInteractions(permits, executor);
    }

    @DisplayName("대기 작업 확인 중 대기열을 읽지 못하면 아무것도 바꾸지 않고 다음 주기를 기다린다.")
    @Test
    void waitsWhenWaitingCheckFails() {
        when(queue.hasWaiting()).thenThrow(new CouponIssueQueueUnavailableException(null));

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.IDLE);
        assertThat(admission.isOpen()).isTrue();
        verifyNoInteractions(permits, executor);
    }

    @DisplayName("permit이 없으면 작업을 가져오지 않고 반환 명령도 보내지 않는다.")
    @Test
    void leavesRequestWhenNoPermit() {
        when(queue.hasWaiting()).thenReturn(true);
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(false);

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.NO_PERMIT);
        verify(queue, never()).claim(any());
        verify(executor, never()).returnPermit(any());
    }

    @DisplayName("permit 응답을 확인하지 못하면 작업을 가져오지 않고 신규 수용을 닫는다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("permitFailures")
    void stopsWhenPermitResultIsUnknown(RuntimeException failure) {
        when(queue.hasWaiting()).thenReturn(true);
        when(permits.tryAcquire(anyString(), eq(3))).thenThrow(failure);

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);
        assertThat(admission.isOpen()).isFalse();
        verify(queue, never()).claim(any());
        verify(executor, never()).returnPermit(any());
    }

    private static Stream<Named<RuntimeException>> permitFailures() {
        return Stream.of(
            Named.of("응답 유실", new CouponPermitStoreUnavailableException(null)),
            Named.of("스크립트 오류", new CouponPermitScriptException(null))
        );
    }

    @DisplayName("permit을 얻었지만 그사이 대기 작업이 사라졌으면 같은 token의 permit만 반환한다.")
    @Test
    void returnsPermitWhenQueueBecameEmpty() {
        whenPermitAcquired();
        when(queue.claim(anyString())).thenReturn(new CouponIssueQueueClaim.Empty());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.IDLE);
        String token = permitToken();
        verify(queue).claim(token);
        verify(executor).returnPermit(token);
        verify(executor, never()).execute(any(), any(), any());
    }

    @DisplayName("작업 가져오기 응답을 받지 못하면 DB 발급을 시작하지 않고 permit 반환을 시도한 뒤 수용을 닫는다.")
    @Test
    void stopsWithoutIssuingWhenClaimResultIsUnknown() {
        whenPermitAcquired();
        when(queue.claim(anyString())).thenThrow(new CouponIssueQueueUnavailableException(null));

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);
        assertThat(admission.isOpen()).isFalse();
        String token = permitToken();
        verify(executor).returnPermit(token);
        verify(executor, never()).execute(any(), any(), any());
    }

    @DisplayName("새로 발급하면 permit을 얻은 token으로 작업을 가져와 발급하고 같은 token으로 성공을 기록한다.")
    @Test
    void recordsSuccessForIssuedCouponWithSameToken() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new Issued(ISSUED));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(101L))).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
        String token = permitToken();
        verify(queue).claim(token);
        verify(executor).execute(CAMPAIGN_ID, USER_ID, token);
        verify(queue).recordSucceeded(REQUEST_ID, token, 101L);
    }

    @DisplayName("중복 충돌 뒤 기존 쿠폰을 찾았으면 그 쿠폰으로 성공을 기록한다.")
    @Test
    void recordsSuccessForAlreadyIssuedCoupon() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new AlreadyIssued(coupon()));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(105L))).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
    }

    @DisplayName("DB 작업 종료를 확인하지 못한 실패는 결과 불명으로 기록하고 쿠폰을 조회하지 않는다.")
    @Test
    void recordsUnknownWhenDbWorkIsNotConfirmed() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString()))
            .thenReturn(new Failed(new DataAccessResourceFailureException("commit 응답 없음"), false));
        when(queue.recordUnknown(eq(REQUEST_ID), anyString())).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
        String token = permitToken();
        verify(queue).recordUnknown(REQUEST_ID, token);
        verifyNoInteractions(coupons);
    }

    @DisplayName("DB 작업은 끝났지만 쿠폰이 남아 있으면 예외와 관계없이 성공으로 기록한다.")
    @Test
    void recordsSuccessWhenCouponExistsAfterFinishedFailure() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString()))
            .thenReturn(new Failed(new IllegalStateException("commit 뒤 후속 처리 실패"), true));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(coupon()));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(105L))).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
    }

    @DisplayName("DB 작업이 끝났고 쿠폰이 없으면 예외로 실패 사유를 정해 기록한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("finishedFailures")
    void recordsFailureReason(RuntimeException failure, CouponIssueRequestReason expected) {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new Failed(failure, true));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(queue.recordFailed(eq(REQUEST_ID), anyString(), eq(expected))).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
    }

    private static Stream<Arguments> finishedFailures() {
        return Stream.of(
            Arguments.of(Named.of("품절", new CouponNotIssuableException(CouponNotIssuableException.Reason.SOLD_OUT)),
                CouponIssueRequestReason.SOLD_OUT),
            Arguments.of(Named.of("기간 밖", new CouponNotIssuableException(CouponNotIssuableException.Reason.OUT_OF_PERIOD)),
                CouponIssueRequestReason.CAMPAIGN_NOT_ISSUABLE),
            Arguments.of(Named.of("캠페인 없음", new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다.")),
                CouponIssueRequestReason.CAMPAIGN_NOT_FOUND),
            Arguments.of(Named.of("잠금 대기 timeout", new CannotAcquireLockException("잠금 대기 시간 초과")),
                CouponIssueRequestReason.DB_ERROR),
            Arguments.of(Named.of("트랜잭션 시작 실패", new CannotCreateTransactionException("연결 실패")),
                CouponIssueRequestReason.DB_ERROR),
            Arguments.of(Named.of("그 밖의 오류", new CoreException(ErrorType.INTERNAL_ERROR, "배정 실패")),
                CouponIssueRequestReason.INTERNAL_ERROR)
        );
    }

    @DisplayName("DB 작업이 끝났어도 쿠폰 확인이 실패하면 실제 결과를 모르므로 결과 불명으로 기록한다.")
    @Test
    void recordsUnknownWhenCouponCheckFails() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString()))
            .thenReturn(new Failed(new CannotAcquireLockException("잠금 대기 시간 초과"), true));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenThrow(new DataAccessResourceFailureException("조회 실패"));
        when(queue.recordUnknown(eq(REQUEST_ID), anyString())).thenReturn(applied());

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
    }

    @DisplayName("같은 결과가 이미 기록돼 있으면 처리한 것으로 보고 수용을 유지한다.")
    @Test
    void treatsAlreadyAppliedAsProcessed() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new Issued(ISSUED));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(101L)))
            .thenReturn(new CouponIssueQueueRecord.AlreadyApplied(processing("same-token")));

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.PROCESSED);
        assertThat(admission.isOpen()).isTrue();
    }

    @DisplayName("다른 실행이 작업을 소유해 기록이 거절되면 신규 수용을 닫고 멈춘다.")
    @Test
    void stopsWhenRecordIsRejected() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new Issued(ISSUED));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(101L)))
            .thenReturn(new CouponIssueQueueRecord.Rejected(processing("other-token")));

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);
        assertThat(admission.isOpen()).isFalse();
    }

    @DisplayName("기록 응답을 받지 못하면 신규 수용을 닫고, 저장된 상태를 한 번 읽은 뒤 멈춘다.")
    @Test
    void stopsAndInspectsWhenRecordResultIsUnknown() {
        whenRequestClaimed();
        when(executor.execute(eq(CAMPAIGN_ID), eq(USER_ID), anyString())).thenReturn(new Issued(ISSUED));
        when(queue.recordSucceeded(eq(REQUEST_ID), anyString(), eq(101L)))
            .thenThrow(new CouponIssueQueueUnavailableException(null));
        when(queue.findById(REQUEST_ID)).thenReturn(Optional.of(processing("same-token")));

        assertThat(worker.runOnce()).isEqualTo(CouponIssueQueueWorkResult.STOPPED);
        assertThat(admission.isOpen()).isFalse();
        verify(queue).findById(REQUEST_ID);
    }

    private void whenPermitAcquired() {
        when(queue.hasWaiting()).thenReturn(true);
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(true);
    }

    private void whenRequestClaimed() {
        whenPermitAcquired();
        when(queue.claim(anyString())).thenAnswer(invocation ->
            new CouponIssueQueueClaim.Claimed(processing(invocation.getArgument(0))));
    }

    /**
     * worker가 이번 실행에서 만든 token. permit 요청에 쓰인 값을 읽어 이후 단계와 비교한다.
     */
    private String permitToken() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(permits).tryAcquire(token.capture(), eq(3));
        assertThat(token.getValue()).isNotBlank();
        return token.getValue();
    }

    private static CouponIssueRequest processing(String token) {
        return CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, USER_ID, CouponIssueRequestStatus.PROCESSING,
            NOW.minusSeconds(5), null, null, token);
    }

    private static CouponIssueQueueRecord applied() {
        return new CouponIssueQueueRecord.Applied(processing("same-token"));
    }

    private static Coupon coupon() {
        return Coupon.reconstitute(105L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(2));
    }
}
