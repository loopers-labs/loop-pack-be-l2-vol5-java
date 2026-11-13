package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponAdmissionException;
import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.coupon.support.CouponSlotRetryTiming;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.infrastructure.concurrency.coupon.SemaphoreCouponAdmission;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

class IssueCouponFacadeTest {
    private final CouponIssueLookupService lookup = mock(CouponIssueLookupService.class);
    private final IssueCouponService issuance = mock(IssueCouponService.class);
    private final CouponExecutionEvidence evidence = mock(CouponExecutionEvidence.class);
    private final CouponAdmissionControl control = new CouponAdmissionControl(
        new SemaphoreCouponAdmission(new CouponAdmissionProperties(3)), evidence);
    private final Timing timing = new Timing();
    private final IssueCouponFacade facade = new IssueCouponFacade(lookup, issuance, control, timing);
    private final CouponIssueCommand command = new CouponIssueCommand(1L, 11L);
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(lookup.findExisting(command)).thenReturn(Optional.empty());
        when(evidence.readyAfterRestart(List.of())).thenReturn(true);
        control.verifyRestart(control.status().bootId(), true, "unit-test", true, List.of());
        control.open(control.status().bootId());
    }

    @Test
    void retriesAtMostFourTimesWithinSamePermitAfterConfirmedNoWriteRollback() {
        busy(CouponExecution.Completion.ROLLED_BACK, 0);

        assertThatThrownBy(() -> facade.execute(command)).isInstanceOf(CouponSlotBusyException.class);

        assertThat(calls.get()).isEqualTo(4);
        assertThat(timing.pauses).isEqualTo(3);
        assertThat(control.status().executions()).hasSize(1);
        assertThat(control.status().available()).isEqualTo(3);
        assertThat(control.status().open()).isTrue();
    }

    @Test
    void doesNotStartAnotherTransactionAfterSlowAttemptExceedsBudget() {
        busy(CouponExecution.Completion.ROLLED_BACK, 250);

        assertThatThrownBy(() -> facade.execute(command)).isInstanceOf(CouponSlotBusyException.class);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(timing.pauses).isZero();
        assertThat(control.status().available()).isEqualTo(3);
    }

    @Test
    void doesNotRetryWhenCompletionIsUnknown() {
        busy(CouponExecution.Completion.UNKNOWN, 0);

        assertUnknown();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(timing.pauses).isZero();
        assertHeldAndClosed();
    }

    @Test
    void missingCompletionEvidenceDoesNotBecomeConfirmedRollback() {
        busy(CouponExecution.Completion.PENDING, 0);

        assertUnknown();

        assertThat(calls.get()).isEqualTo(1);
        assertHeldAndClosed();
    }

    @Test
    void unknownWriteTransactionCannotStartServiceFollowUpLookup() {
        IssueCouponTxService transactions = mock(IssueCouponTxService.class);
        IssueCouponService realService = new IssueCouponService(transactions, lookup);
        CouponExecution execution = new CouponExecution("unit:unknown");
        when(transactions.execute(command, execution)).thenAnswer(invocation -> {
            complete(execution, CouponExecution.Completion.UNKNOWN);
            throw new CouponSlotBusyException();
        });

        assertThatThrownBy(() -> realService.execute(command, execution)).isInstanceOf(CouponAdmissionException.class);

        verify(lookup, never()).resolveUnavailableSlot(command, execution);
    }

    @Test
    void commitCommunicationExceptionHoldsPermitEvenWithCommittedCallback() {
        doAnswer(invocation -> {
            complete(invocation.getArgument(1), CouponExecution.Completion.COMMITTED);
            throw new TransactionSystemException("simulated commit communication failure");
        }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));

        assertUnknown();

        assertHeldAndClosed();
    }

    @Test
    void laterTransactionCreationFailureCannotReuseEarlierRollbackEvidence() {
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new CannotCreateTransactionException("simulated retry transaction creation failure");
            }
            complete(invocation.getArgument(1), CouponExecution.Completion.ROLLED_BACK);
            throw new CouponSlotBusyException();
        }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));

        assertUnknown();

        assertThat(calls.get()).isEqualTo(2);
        assertHeldAndClosed();
    }

    @Test
    void closeDuringRetryWaitPreventsAnotherAuthorizedAttempt() {
        busy(CouponExecution.Completion.ROLLED_BACK, 0);
        timing.afterPause = () -> control.close("manual-close");

        assertThatThrownBy(() -> facade.execute(command)).isInstanceOfSatisfying(CouponAdmissionException.class,
            failure -> assertThat(failure.getErrorCode())
                .isEqualTo(CouponAdmissionException.Code.COUPON_ADMISSION_UNAVAILABLE));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(control.status().available()).isEqualTo(3);
    }

    @Test
    void interruptionPreservesFlagAndReturnsPermitOnlyAfterKnownTransactionEnd() {
        busy(CouponExecution.Completion.ROLLED_BACK, 0);
        timing.interrupted = true;
        try {
            assertThatThrownBy(() -> facade.execute(command)).isInstanceOf(CouponSlotBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(control.status().available()).isEqualTo(3);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failureBeforeTransactionObservationIsHeldInsteadOfGuessingRollback() {
        when(issuance.execute(any(CouponIssueCommand.class), any(CouponExecution.class)))
            .thenThrow(new IllegalStateException("proxy entry failed"));

        assertUnknown();

        assertHeldAndClosed();
    }

    private void busy(CouponExecution.Completion completion, long millis) {
        doAnswer(invocation -> {
            calls.incrementAndGet();
            complete(invocation.getArgument(1), completion);
            timing.current += TimeUnit.MILLISECONDS.toNanos(millis);
            throw new CouponSlotBusyException();
        }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));
    }

    private static void complete(CouponExecution execution, CouponExecution.Completion completion) {
        int index = execution.started();
        execution.identify(index, new CouponExecution.DbWork("unit-server", 1));
        execution.completed(index, completion);
    }

    private void assertUnknown() {
        assertThatThrownBy(() -> facade.execute(command)).isInstanceOfSatisfying(CouponAdmissionException.class,
            failure -> assertThat(failure.getErrorCode())
                .isEqualTo(CouponAdmissionException.Code.COUPON_ISSUE_OUTCOME_UNKNOWN));
    }

    private void assertHeldAndClosed() {
        assertThat(control.status().available()).isEqualTo(2);
        assertThat(control.status().held()).isEqualTo(1);
        assertThat(control.status().open()).isFalse();
    }

    private static final class Timing extends CouponSlotRetryTiming {
        private long current;
        private int pauses;
        private boolean interrupted;
        private Runnable afterPause = () -> {};

        @Override public long now() { return current; }

        @Override
        public void pause() throws InterruptedException {
            pauses++;
            if (interrupted) {
                throw new InterruptedException("unit-test");
            }
            current += TimeUnit.MILLISECONDS.toNanos(50);
            afterPause.run();
        }
    }
}
