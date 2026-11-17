package com.loopers.application.coupon;

import com.loopers.application.user.UserResolver;
import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.coupon.CouponException;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@Transactional(propagation = Propagation.NEVER)
public class CouponAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(CouponAdmissionService.class);
    private static final int RECOVERY_BATCH_SIZE = 100;

    private final UserResolver users;
    private final CouponSelectionStore selections;
    private final CouponIssuanceService reservations;
    // Reconciliation must drain local submissions, including their MySQL commits.
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);

    public CouponAdmissionService(UserResolver users, CouponSelectionStore selections, CouponIssuanceService reservations) {
        this.users = users;
        this.selections = selections;
        this.reservations = reservations;
    }

    public CouponReservationInfo apply(String requester, long campaignId) {
        long userId = users.resolve(requester).userId();
        lifecycle.readLock().lock();
        try {
            return switch (selections.select(campaignId, userId)) {
                case FULL -> throw new CouponException(CouponException.Reason.SOLD_OUT);
                case INACTIVE -> throw new CouponException(CouponException.Reason.INACTIVE);
                case NEW, EXISTING -> reserveAndComplete(campaignId, userId);
            };
        } catch (CouponSelectionUnavailableException failure) {
            // A closed gate can still return a receipt already committed for this customer.
            return readCommittedOrUnavailable(campaignId, userId, failure);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void open(long campaignId) {
        lifecycle.writeLock().lock();
        try {
            selections.close(campaignId);
            var snapshot = reservations.selectionSnapshot(campaignId);
            selections.open(campaignId, snapshot.totalQuota(), snapshot.reservedUsers(), snapshot.active());
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public void resumePending(long campaignId) {
        lifecycle.readLock().lock();
        try {
            for (long userId : selections.pending(campaignId, RECOVERY_BATCH_SIZE)) {
                reserveAndComplete(campaignId, userId);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private CouponReservationInfo reserveAndComplete(long campaignId, long userId) {
        CouponReservationInfo committed;
        try {
            committed = reservations.reserve(campaignId, userId);
        } catch (CouponException conflict) {
            // Redis accepted a new selection but the locked DB campaign rejected it.
            selections.close(campaignId);
            log.warn("Closed coupon admission for campaign {} after DB rejected a selection: {}", campaignId, conflict.reason());
            throw conflict;
        } catch (UserResolutionException missingStoredUser) {
            selections.close(campaignId);
            log.warn("Closed coupon admission for campaign {} because the selected identity has no DB user", campaignId);
            throw unavailable(missingStoredUser);
        } catch (DataAccessException | TransactionException | PersistenceException failure) {
            committed = readCommittedOrUnavailable(campaignId, userId, failure);
        }
        try {
            selections.complete(campaignId, userId);
        } catch (CouponSelectionUnavailableException failure) {
            log.warn("Redis completion unconfirmed for committed coupon reservation campaign={} reservation={}",
                campaignId, committed.reservationId(), failure);
        }
        return committed;
    }

    private CouponReservationInfo readCommittedOrUnavailable(long campaignId, long userId, RuntimeException failure) {
        try {
            return reservations.findCommitted(campaignId, userId).orElseThrow(() -> unavailable(failure));
        } catch (DataAccessException | TransactionException | PersistenceException readFailure) {
            selections.close(campaignId);
            log.warn("Closed coupon admission after reservation verification failed, campaign={}", campaignId, readFailure);
            failure.addSuppressed(readFailure);
            throw unavailable(failure);
        }
    }

    private CouponException unavailable(RuntimeException failure) {
        return new CouponException(CouponException.Reason.ADMISSION_UNAVAILABLE, failure);
    }
}
