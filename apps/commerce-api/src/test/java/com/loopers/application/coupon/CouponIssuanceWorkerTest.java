package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponIssuanceWorkerTest {
    private static final List<Long> CAMPAIGNS = List.of(42L);
    private final CouponIssuanceService database = mock(CouponIssuanceService.class);
    private final CouponIssueSemaphore slots = mock(CouponIssueSemaphore.class);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final CouponIssuanceWorker worker = new CouponIssuanceWorker(database, slots, tasks::addLast, 2);

    private static CouponReservationInfo waiting(long id) {
        return new CouponReservationInfo(id, 42L, id, CouponStatus.WAITING, null);
    }

    private void enable() {
        when(slots.isOpen()).thenReturn(true);
        when(slots.tryAcquire(any())).thenReturn(true);
    }

    @Test
    void distinctTokensStayHeldForEachOutstandingTaskAndDuplicatePollsSkipTheirReservations() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1), waiting(2)))
            .thenReturn(List.of(waiting(2), waiting(3)));
        worker.poll(CAMPAIGNS);
        worker.poll(CAMPAIGNS);
        var tokens = ArgumentCaptor.forClass(UUID.class);
        verify(slots, times(2)).tryAcquire(tokens.capture());
        assertThat(tokens.getAllValues()).doesNotHaveDuplicates();
        verify(slots, never()).release(any());
        assertThat(tasks).hasSize(2);

        tasks.removeFirst().run();
        worker.poll(CAMPAIGNS);
        assertThat(tasks).hasSize(2);
        tasks.removeFirst().run();
        tasks.removeFirst().run();

        var ordered = inOrder(database, slots);
        ordered.verify(database).issue(42L, 1L);
        ordered.verify(slots).release(tokens.getAllValues().get(0));
        ordered.verify(database).issue(42L, 2L);
        ordered.verify(slots).release(tokens.getAllValues().get(1));
        ordered.verify(database).issue(42L, 3L);
    }

    @Test
    void aFullSemaphoreLeavesTheHeadWaitingAndSubmitsNoLaterReservation() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(4), waiting(5)));
        when(slots.tryAcquire(any())).thenReturn(false);
        worker.poll(CAMPAIGNS);
        assertThat(tasks).isEmpty();
        verify(slots).tryAcquire(any());
        verify(slots, never()).release(any());
    }

    @Test
    void aFailedIssueCallReturnsItsTokenAfterASeparateLockingReadVerifiesRollback() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1)));
        when(database.issue(42L, 1L)).thenThrow(new DataAccessResourceFailureException("TX interrupted"));
        when(database.verifyIssuance(42L, 1L)).thenReturn(waiting(1));
        worker.poll(CAMPAIGNS);
        tasks.removeFirst().run();

        var ordered = inOrder(database, slots);
        ordered.verify(database).issue(42L, 1L);
        ordered.verify(database).verifyIssuance(42L, 1L);
        ordered.verify(slots).release(any());
        verify(slots, never()).close();
    }

    @Test
    void uncertainDbCompletionRetainsItsTokenUntilAllLocalTasksEndAndRecoveryLocksTheReservation() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1), waiting(2)));
        when(database.issue(42L, 1L)).thenThrow(new DataAccessResourceFailureException("commit unknown"));
        when(database.verifyIssuance(42L, 1L)).thenThrow(new DataAccessResourceFailureException("DB unavailable"))
            .thenReturn(waiting(1));
        worker.poll(CAMPAIGNS);
        var tokens = ArgumentCaptor.forClass(UUID.class);
        verify(slots, times(2)).tryAcquire(tokens.capture());

        tasks.removeFirst().run();
        verify(slots).close();
        verify(slots, never()).release(any());
        assertThatThrownBy(worker::open).isInstanceOf(IllegalStateException.class);
        verify(slots, never()).open();

        tasks.removeFirst().run();
        worker.open();
        verify(database, times(2)).verifyIssuance(42L, 1L);
        verify(slots).release(tokens.getAllValues().get(0));
        verify(slots).release(tokens.getAllValues().get(1));
        verify(slots).open();
    }

    @Test
    void executorRejectionReturnsOnlyTheAcquiredTokenWithoutCallingIssue() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1)));
        var rejecting = new CouponIssuanceWorker(database, slots, task -> {
            throw new RejectedExecutionException();
        }, 2);
        rejecting.poll(CAMPAIGNS);
        var token = ArgumentCaptor.forClass(UUID.class);
        verify(slots).tryAcquire(token.capture());
        verify(slots).release(token.getValue());
        verify(database, never()).issue(42L, 1L);
    }

    @Test
    void uncertainAcquireLeavesTheWorkUnstartedAndStopsPolling() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1), waiting(2)));
        when(slots.tryAcquire(any())).thenThrow(CouponIssueSemaphoreUnavailableException.class);
        worker.poll(CAMPAIGNS);
        assertThat(tasks).isEmpty();
        verify(slots).close();
        verify(slots, never()).release(any());
    }

    @Test
    void lostReleaseReplyAfterCommitClosesTheGateWithoutRepeatingIssuance() {
        enable();
        when(database.findWaiting(CAMPAIGNS, 2)).thenReturn(List.of(waiting(1)));
        doThrow(CouponIssueSemaphoreUnavailableException.class).when(slots).release(any());
        worker.poll(CAMPAIGNS);
        tasks.removeFirst().run();
        verify(slots).close();
        verify(database).issue(42L, 1L);
        verify(database, never()).verifyIssuance(42L, 1L);
    }
}
