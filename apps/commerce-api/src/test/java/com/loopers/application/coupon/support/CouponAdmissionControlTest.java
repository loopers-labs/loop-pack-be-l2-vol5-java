package com.loopers.application.coupon.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponAdmission;
import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.support.CouponAdmissionException.Code;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.infrastructure.concurrency.coupon.SemaphoreCouponAdmission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

class CouponAdmissionControlTest {
    private final Evidence evidence = new Evidence();
    private final CouponAdmission admission = new SemaphoreCouponAdmission(new CouponAdmissionProperties(3));
    private final CouponAdmissionControl control = new CouponAdmissionControl(admission, evidence);

    @Test
    void startsClosedAndRequiresProcessAndActualDatabaseEvidenceBeforeOpening() {
        assertThatThrownBy(() -> control.acquire(command(1))).isInstanceOf(CouponAdmissionException.class);
        String boot = control.status().bootId();
        assertThatThrownBy(() -> control.open(boot)).isInstanceOf(CouponAdmissionException.class);
        assertThatThrownBy(() -> control.verifyRestart(boot, false, "ops-1", true, List.of())).isInstanceOf(CouponAdmissionException.class);
        evidence.ready = false;
        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops-1", true, List.of())).isInstanceOf(CouponAdmissionException.class);
        evidence.ready = true;
        control.verifyRestart(boot, true, "ops-1", true, List.of());
        assertThat(control.status().open()).isFalse();
        control.open(boot);
        assertThat(control.status().open()).isTrue();
    }

    @Test
    void restartMustNamePreviousSessionsAndInitialDeploymentCannotHideAProvidedSession() {
        String boot = control.status().bootId();
        List<CouponExecution.DbWork> previous = List.of(new CouponExecution.DbWork("previous-server", 17L));
        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops-restart", false, List.of()))
            .isInstanceOf(CouponAdmissionException.class);
        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops-restart", true, previous))
            .isInstanceOf(CouponAdmissionException.class);
        evidence.ready = false;
        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops-restart", false, previous))
            .isInstanceOf(CouponAdmissionException.class);
        evidence.ready = true;

        control.verifyRestart(boot, true, "ops-restart", false, previous);

        assertThat(control.status().restartVerified()).isTrue();
        assertThat(control.status().open()).isFalse();
    }

    @Test
    void capacityIsSharedAndPermitsAreReturnedOnlyOnceAfterExecutionAndTransactionEnd() {
        open();
        CouponAdmissionControl.Handle first = control.acquire(command(1));
        CouponAdmissionControl.Handle second = control.acquire(command(2));
        CouponAdmissionControl.Handle third = control.acquire(new CouponIssueCommand(2L, 3L));
        assertThat(admission.available()).isZero();
        assertThatThrownBy(() -> control.acquire(command(4))).isInstanceOfSatisfying(CouponAdmissionException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(Code.COUPON_CAPACITY_EXCEEDED));
        complete(first, CouponExecution.Completion.COMMITTED);
        assertThat(admission.available()).isZero();
        assertThat(control.finish(first, false)).isTrue();
        assertThat(control.finish(first, false)).isTrue();
        assertThat(admission.available()).isEqualTo(1);
        complete(second, CouponExecution.Completion.ROLLED_BACK);
        control.finish(second, false);
        complete(third, CouponExecution.Completion.COMMITTED);
        control.finish(third, false);
        assertThat(admission.available()).isEqualTo(3);
    }

    @Test
    void recoversOnlySpecifiedEndedLocalExecutionsWithDatabaseEvidenceAndKeepsClosed() {
        open();
        CouponAdmissionControl.Handle unknown = control.acquire(command(1));
        CouponAdmissionControl.Handle running = control.acquire(command(2));
        complete(unknown, CouponExecution.Completion.UNKNOWN);
        assertThat(control.finish(unknown, false)).isFalse();
        assertThat(control.status().held()).isEqualTo(1);
        assertThat(control.status().running()).isEqualTo(1);
        assertThat(control.status().open()).isFalse();
        String boot = control.status().bootId();
        String id = unknown.execution().id();
        evidence.ended = false;
        assertThat(control.recover(boot, List.of(id)).getFirst().result())
            .isEqualTo(CouponAdmissionControl.Recovery.NOT_ENDED);
        evidence.ended = true;
        assertThat(control.recover(boot, List.of(id, id, running.execution().id(), boot + ":missing", "old:1")))
            .extracting(CouponAdmissionControl.RecoveryResult::result)
            .containsExactly(CouponAdmissionControl.Recovery.RELEASED, CouponAdmissionControl.Recovery.ALREADY_RELEASED,
                CouponAdmissionControl.Recovery.NOT_ENDED, CouponAdmissionControl.Recovery.NOT_FOUND,
                CouponAdmissionControl.Recovery.OTHER_BOOT);
        assertThat(admission.available()).isEqualTo(2);
        assertThat(control.status().open()).isFalse();
        assertThatThrownBy(() -> control.open(boot)).isInstanceOf(CouponAdmissionException.class);
        complete(running, CouponExecution.Completion.COMMITTED);
        control.finish(running, false);
        control.open(boot);
        assertThat(admission.available()).isEqualTo(3);
    }

    @Test
    void independentInstancesAndOldBootRecoveryDoNotIncreaseNewCapacity() {
        open();
        CouponAdmissionControl.Handle handle = control.acquire(command(1));
        CouponAdmission other = new SemaphoreCouponAdmission(new CouponAdmissionProperties(5));
        CouponAdmissionControl restarted = new CouponAdmissionControl(other, evidence);
        assertThat(restarted.recover(control.status().bootId(), List.of(handle.execution().id())).getFirst().result())
            .isEqualTo(CouponAdmissionControl.Recovery.OTHER_BOOT);
        control.close("ops");
        assertThat(other.available()).isEqualTo(5);
        assertThat(admission.available()).isEqualTo(2);
        assertThat(restarted.status().open()).isFalse();
    }

    @Test
    void recoveryEvidenceProxyFailureHoldsOnlyThatExecutionAndContinuesOtherIds() {
        open();
        CouponAdmissionControl.Handle failed = control.acquire(command(1));
        CouponAdmissionControl.Handle recoverable = control.acquire(command(2));
        complete(failed, CouponExecution.Completion.UNKNOWN);
        complete(recoverable, CouponExecution.Completion.UNKNOWN);
        recoverable.execution().identify(0, new CouponExecution.DbWork("server", 99L));
        control.finish(failed, false);
        control.finish(recoverable, false);
        evidence.failingConnection = 10L;

        assertThat(control.recover(control.status().bootId(),
            List.of(failed.execution().id(), recoverable.execution().id())))
            .extracting(CouponAdmissionControl.RecoveryResult::result)
            .containsExactly(CouponAdmissionControl.Recovery.NOT_ENDED, CouponAdmissionControl.Recovery.RELEASED);

        assertThat(admission.available()).isEqualTo(2);
        assertThat(control.status().held()).isEqualTo(1);
        assertThat(control.status().open()).isFalse();
    }

    @Test
    void restartEvidenceProxyFailureRejectsVerificationAndKeepsClosed() {
        evidence.restartFailure = true;

        assertThatThrownBy(() -> control.verifyRestart(control.status().bootId(), true, "ops", true, List.of()))
            .isInstanceOfSatisfying(CouponAdmissionException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(Code.COUPON_RECOVERY_REJECTED));

        assertThat(control.status().restartVerified()).isFalse();
        assertThat(control.status().open()).isFalse();
        assertThat(admission.available()).isEqualTo(3);
    }

    @Test
    void failedReverificationCannotReusePreviousSuccessForEitherFalseOrThrownEvidence() {
        open();
        control.close("reverify");
        String boot = control.status().bootId();
        evidence.ready = false;

        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops", true, List.of()))
            .isInstanceOf(CouponAdmissionException.class);
        assertThat(control.status().restartVerified()).isFalse();
        assertThatThrownBy(() -> control.open(boot)).isInstanceOf(CouponAdmissionException.class);

        evidence.ready = true;
        open();
        control.close("reverify-again");
        evidence.restartFailure = true;

        assertThatThrownBy(() -> control.verifyRestart(boot, true, "ops", true, List.of()))
            .isInstanceOf(CouponAdmissionException.class);
        assertThat(control.status().restartVerified()).isFalse();
        assertThatThrownBy(() -> control.open(boot)).isInstanceOf(CouponAdmissionException.class);
        assertThat(control.status().open()).isFalse();
        assertThat(admission.available()).isEqualTo(3);
    }

    private void open() {
        String boot = control.status().bootId();
        control.verifyRestart(boot, true, "test-process-stopped", true, List.of());
        control.open(boot);
    }

    private static CouponIssueCommand command(long user) { return new CouponIssueCommand(1L, user); }

    private static void complete(CouponAdmissionControl.Handle handle, CouponExecution.Completion completion) {
        int index = handle.execution().started();
        handle.execution().identify(index, new CouponExecution.DbWork("server", 10L));
        handle.execution().completed(index, completion);
    }

    private static final class Evidence implements CouponExecutionEvidence {
        private boolean ready = true;
        private boolean ended = true;
        private long failingConnection = -1L;
        private boolean restartFailure;

        @Override
        public boolean readyAfterRestart(List<CouponExecution.DbWork> previousSessions) {
            if (restartFailure) {
                throw new CannotCreateTransactionException("test-evidence-start-failure");
            }
            return ready;
        }

        @Override
        public boolean ended(CouponExecution.DbWork work, long campaignId) {
            if (work.connectionId() == failingConnection) {
                throw new CannotCreateTransactionException("test-evidence-start-failure");
            }
            return ended;
        }
    }
}
