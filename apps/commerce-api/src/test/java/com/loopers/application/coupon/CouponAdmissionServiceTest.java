package com.loopers.application.coupon;

import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.coupon.CouponException;
import com.loopers.domain.coupon.CouponStatus;
import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponAdmissionServiceTest {
    private final UserResolver users = mock(UserResolver.class);
    private final CouponSelectionStore selections = mock(CouponSelectionStore.class);
    private final CouponIssuanceService database = mock(CouponIssuanceService.class);
    private final CouponAdmissionService service = new CouponAdmissionService(users, selections, database);

    private void admit() {
        when(users.resolve("alice")).thenReturn(new UserIdentity(1L, UserRole.CUSTOMER));
        when(selections.select(42L, 1L)).thenReturn(CouponSelectionStore.Selection.NEW);
    }

    @Test
    void redisCompletionFailurePreservesTheKnownCommittedReceipt() {
        admit();
        var committed = new CouponReservationInfo(7L, 42L, 1L, CouponStatus.WAITING, null);
        when(database.reserve(42L, 1L)).thenReturn(committed);
        doThrow(new CouponSelectionUnavailableException()).when(selections).complete(42L, 1L);

        assertThat(service.apply("alice", 42L)).isEqualTo(committed);
        verify(database).reserve(42L, 1L);
    }

    @Test
    void failureToVerifyAnUncertainCommitClosesAdmissionAndRetainsTheWork() {
        admit();
        when(database.reserve(42L, 1L)).thenThrow(new DataAccessResourceFailureException("commit interrupted"));
        when(database.findCommitted(42L, 1L)).thenThrow(new DataAccessResourceFailureException("lookup unavailable"));

        assertUnavailableAndStillPending();
    }

    @Test
    void aResolvedIdentityMissingFromMysqlStopsNewAdmissionForThatCampaign() {
        admit();
        when(database.reserve(42L, 1L)).thenThrow(UserResolutionException.userNotFound());
        assertUnavailableAndStillPending();
    }

    @Test
    void unavailableSelectionReadsOnlyTheRequestersAlreadyCommittedReceipt() {
        admit();
        doThrow(new CouponSelectionUnavailableException()).when(selections).select(42L, 1L);
        var issued = new CouponReservationInfo(7L, 42L, 1L, CouponStatus.ISSUED, 19L);
        when(database.findCommitted(42L, 1L)).thenReturn(Optional.of(issued));

        assertThat(service.apply("alice", 42L)).isEqualTo(issued);
        verify(database, never()).reserve(42L, 1L);
        verify(selections, never()).complete(42L, 1L);
    }

    @Test
    void unavailableSelectionWithNoCommittedReservationDeniesNewWork() {
        admit();
        doThrow(new CouponSelectionUnavailableException()).when(selections).select(42L, 1L);

        assertThatExceptionOfType(CouponException.class).isThrownBy(() -> service.apply("alice", 42L))
            .satisfies(error -> assertThat(error.reason()).isEqualTo(CouponException.Reason.ADMISSION_UNAVAILABLE));
        verify(database, never()).reserve(42L, 1L);
        verify(selections, never()).complete(42L, 1L);
    }

    private void assertUnavailableAndStillPending() {
        assertThatExceptionOfType(CouponException.class).isThrownBy(() -> service.apply("alice", 42L))
            .satisfies(error -> assertThat(error.reason()).isEqualTo(CouponException.Reason.ADMISSION_UNAVAILABLE));
        verify(selections).close(42L);
        verify(selections, never()).complete(42L, 1L);
    }
}
