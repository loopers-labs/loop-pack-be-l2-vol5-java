package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueExecution.AlreadyIssued;
import com.loopers.application.coupon.CouponIssueExecution.Failed;
import com.loopers.application.coupon.CouponIssueExecution.Issued;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponNotIssuableException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.DuplicateCouponIssueException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static com.loopers.application.coupon.CouponIssueTransactionEndings.endsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Long CAMPAIGN_ID = 41L;
    private static final Long USER_ID = 73L;
    private static final String TOKEN = "worker-or-request-token";

    @Mock private CouponRepository coupons;
    @Mock private CouponIssuePermits permits;
    @Mock private CouponIssueTransaction transaction;

    private CouponIssueAdmissionState admission;
    private CouponIssueExecutor executor;

    @BeforeEach
    void setUp() {
        admission = new CouponIssueAdmissionState();
        admission.open();
        executor = new CouponIssueExecutor(coupons, permits, transaction, admission);
    }

    @DisplayName("새로 발급하고 commit하면 발급 결과를 돌려주고 받은 token을 반환한다.")
    @Test
    void returnsIssuedCouponAndReleasesToken() {
        CouponIssueInfo issued = new CouponIssueInfo(101L, CAMPAIGN_ID, NOW, "5,000원 할인권");
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any())).thenReturn(issued);
        when(permits.release(TOKEN)).thenReturn(1L);

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isEqualTo(new Issued(issued));
        verify(permits).release(TOKEN);
        assertThat(admission.isOpen()).isTrue();
    }

    @DisplayName("commit 뒤 token 반환 결과가 0이면 발급 결과는 유지하고 신규 수용을 닫는다.")
    @Test
    void keepsIssuedResultAndClosesAdmissionWhenTokenWasMissing() {
        CouponIssueInfo issued = new CouponIssueInfo(101L, CAMPAIGN_ID, NOW, "5,000원 할인권");
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any())).thenReturn(issued);
        when(permits.release(TOKEN)).thenReturn(0L);

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isEqualTo(new Issued(issued));
        assertThat(admission.isOpen()).isFalse();
    }

    @DisplayName("같은 사용자의 중복 충돌로 rollback되면 token을 반환한 뒤 새 조회로 찾은 기존 쿠폰을 돌려준다.")
    @Test
    void returnsExistingCouponAfterDuplicateRollback() {
        Coupon existing = Coupon.reconstitute(105L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(2));
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any())).thenAnswer(endsWith(false,
            TransactionSynchronization.STATUS_ROLLED_BACK, new DuplicateCouponIssueException(null)));
        when(permits.release(TOKEN)).thenReturn(1L);
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(existing));

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isEqualTo(new AlreadyIssued(existing));
        verify(permits).release(TOKEN);
    }

    @DisplayName("중복 충돌 뒤 기존 쿠폰을 찾지 못하면 DB 작업이 끝난 내부 오류로 돌려준다.")
    @Test
    void failsWhenExistingCouponIsMissingAfterDuplicateRollback() {
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any())).thenAnswer(endsWith(false,
            TransactionSynchronization.STATUS_ROLLED_BACK, new DuplicateCouponIssueException(null)));
        when(permits.release(TOKEN)).thenReturn(1L);
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isInstanceOfSatisfying(Failed.class, failed -> {
            assertThat(failed.dbWorkDone()).isTrue();
            assertThat(failed.failure()).isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
        });
    }

    @DisplayName("DB 작업의 종료를 확인한 실패는 원래 예외와 함께 돌려주고 token을 반환한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("finishedFailures")
    void returnsFinishedFailureAndReleasesToken(RuntimeException failure, boolean commitRequested, Integer status) {
        if (status == null) {
            when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any())).thenThrow(failure);
        } else {
            when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any()))
                .thenAnswer(endsWith(commitRequested, status, failure));
        }
        when(permits.release(TOKEN)).thenReturn(1L);

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isEqualTo(new Failed(failure, true));
        verify(permits).release(TOKEN);
        assertThat(admission.isOpen()).isTrue();
        verifyNoInteractions(coupons);
    }

    private static Stream<Arguments> finishedFailures() {
        return Stream.of(
            Arguments.of(Named.of("업무 거절 rollback",
                    new CouponNotIssuableException(CouponNotIssuableException.Reason.SOLD_OUT)),
                false, TransactionSynchronization.STATUS_ROLLED_BACK),
            Arguments.of(Named.of("잠금 대기 timeout rollback",
                    (RuntimeException) new CannotAcquireLockException("캠페인 행 잠금 대기 시간을 넘었습니다.")),
                false, TransactionSynchronization.STATUS_ROLLED_BACK),
            Arguments.of(Named.of("commit 완료 뒤 예외",
                    (RuntimeException) new IllegalStateException("commit 뒤 후속 처리 실패")),
                true, TransactionSynchronization.STATUS_COMMITTED),
            Arguments.of(Named.of("트랜잭션 시작 실패",
                    (RuntimeException) new CannotCreateTransactionException("DB 연결을 얻지 못했습니다.")),
                false, null)
        );
    }

    @DisplayName("DB 작업의 종료를 확인하지 못한 실패는 종료 미확인으로 돌려주고 token을 남긴다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfirmedFailures")
    void returnsUnconfirmedFailureAndKeepsToken(RuntimeException failure, boolean commitRequested, Integer status) {
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any()))
            .thenAnswer(endsWith(commitRequested, status, failure));

        CouponIssueExecution result = executor.execute(CAMPAIGN_ID, USER_ID, TOKEN);

        assertThat(result).isEqualTo(new Failed(failure, false));
        verify(permits, never()).release(any());
        assertThat(admission.isOpen()).isTrue();
    }

    private static Stream<Arguments> unconfirmedFailures() {
        return Stream.of(
            Arguments.of(Named.of("commit 실패로 결과 불명",
                    (RuntimeException) new TransactionSystemException("commit 중 연결이 끊겼습니다.")),
                true, TransactionSynchronization.STATUS_UNKNOWN),
            Arguments.of(Named.of("commit 요청 뒤 실패를 rollback으로 보고",
                    (RuntimeException) new DataAccessResourceFailureException("commit 응답을 받기 전에 연결이 끊겼습니다.")),
                true, TransactionSynchronization.STATUS_ROLLED_BACK),
            Arguments.of(Named.of("완료 상태 기록 없음",
                    (RuntimeException) new IllegalStateException("완료 통지 전에 전달된 예외")),
                false, null)
        );
    }

    @DisplayName("Error는 결과로 감싸지 않고 다시 던지며, DB 작업 종료를 확인했을 때만 token을 반환한다.")
    @Test
    void rethrowsErrorAfterReturningTokenOnlyWhenDbWorkIsDone() {
        StackOverflowError error = new StackOverflowError("테스트용 Error");
        when(transaction.issue(eq(CAMPAIGN_ID), eq(USER_ID), any()))
            .thenAnswer(endsWith(false, TransactionSynchronization.STATUS_ROLLED_BACK, error));
        when(permits.release(TOKEN)).thenReturn(1L);

        assertThatThrownBy(() -> executor.execute(CAMPAIGN_ID, USER_ID, TOKEN)).isSameAs(error);
        verify(permits).release(TOKEN);
    }
}
