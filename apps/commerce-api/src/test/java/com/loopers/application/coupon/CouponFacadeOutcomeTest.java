package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueCancellationRepository;
import com.loopers.domain.coupon.CouponIssueGate;
import com.loopers.domain.coupon.CouponIssueJobs;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.infrastructure.coupon.NoCouponIssueLimiter;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 완료 증거에 따른 보상 판단의 단위 테스트. 실제 commit 응답 유실 재현과 구분한다. */
class CouponFacadeOutcomeTest {
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponIssueTransaction transaction = mock(CouponIssueTransaction.class);
    private final CouponIssueGate gate = mock(CouponIssueGate.class);
    private final CouponFacade facade = new CouponFacade(mock(UserService.class), coupons, mock(CouponCampaignRepository.class),
        transaction, gate, new NoCouponIssueLimiter(), mock(CouponIssueJobs.class),
        mock(CouponIssueCancellationRepository.class), new CouponClosedCampaigns(false, Duration.ofSeconds(10)), Clock.systemUTC(), CouponIssueMode.REDIS_SYNC, Duration.ofHours(1), Duration.ofDays(7));

    @BeforeEach
    void prepare() {
        TransactionSynchronizationManager.initSynchronization();
        when(coupons.findByCampaignIdAndUserId(1L, 2L)).thenReturn(Optional.empty());
        when(gate.enter(any(), any(), any())).thenReturn(new CouponIssueGate.Entry(CouponIssueGate.Decision.PASSED, "R:owned"));
    }

    @AfterEach
    void cleanUp() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test
    void unknownCompletionKeepsReservationAndReturns503() {
        doAnswer(invocation -> {
            CouponIssueExecution execution = invocation.getArgument(2);
            execution.observeTransaction();
            execution.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            throw new TransactionSystemException("unknown DB outcome");
        }).when(transaction).issue(any(), any(), any());

        assertThatThrownBy(() -> facade.issue(2L, 1L)).isInstanceOfSatisfying(CoreException.class,
            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));

        verify(gate, never()).cancel(any(), any(), any());
        verify(gate, never()).confirm(any(), any(), any(), anyBoolean());
    }

    @Test
    void confirmedRollbackCompensatesOnlyOwnedReservationAndKeepsOriginalFailure() {
        doAnswer(invocation -> {
            CouponIssueExecution execution = invocation.getArgument(2);
            execution.observeTransaction();
            execution.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            throw new IllegalStateException("confirmed rollback");
        }).when(transaction).issue(any(), any(), any());

        assertThatThrownBy(() -> facade.issue(2L, 1L)).isInstanceOf(IllegalStateException.class).hasMessage("confirmed rollback");

        verify(gate).cancel(1L, 2L, "R:owned");
    }
}
