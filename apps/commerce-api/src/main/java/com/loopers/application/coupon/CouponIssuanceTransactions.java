package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAlreadyIssuedException;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponIssuanceTransactions {
    private final CouponCampaignRepository campaigns;
    private final CouponRepository coupons;
    private final PlatformTransactionManager transactionManager;

    public enum Outcome {
        COMMITTED, ROLLED_BACK, UNKNOWN
    }

    /** Tracks the last completed transaction: a read-only lookup or an issuance write. */
    public static final class Completion {
        private volatile Outcome outcome = Outcome.UNKNOWN;
        // Conservative until the coordinator explicitly starts a read-only lookup.
        private volatile boolean writeStarted = true;
        private volatile boolean recoveryInProgress;
        private volatile boolean soldOutDetected;

        public Outcome outcome() {
            return outcome;
        }

        public boolean safeToRetry() {
            return !recoveryInProgress && (!writeStarted || outcome == Outcome.ROLLED_BACK);
        }

        /** A failed preflight read is not evidence of a rolled-back issuance write. */
        public boolean writeRolledBack() {
            return writeStarted && !recoveryInProgress && outcome == Outcome.ROLLED_BACK;
        }

        public boolean soldOutDetected() {
            return soldOutDetected;
        }

        private void complete(int status) {
            outcome = switch (status) {
                case TransactionSynchronization.STATUS_COMMITTED -> Outcome.COMMITTED;
                case TransactionSynchronization.STATUS_ROLLED_BACK -> Outcome.ROLLED_BACK;
                default -> Outcome.UNKNOWN;
            };
        }
    }

    public record IssueResult(Coupon coupon, boolean created) {
    }

    public IssueResult issue(long campaignId, long userId, Completion completion) {
        completion.writeStarted = false;
        var existing = lookup(campaignId, userId, completion, true);
        if (existing.isPresent()) {
            return new IssueResult(existing.get(), false);
        }

        // A completed read transaction is not evidence that the following write completed.
        completion.outcome = Outcome.UNKNOWN;
        completion.writeStarted = true;
        try {
            return transaction(false).execute(status -> issueLocked(campaignId, userId, completion));
        } catch (CouponAlreadyIssuedException duplicate) {
            return recoverAfterRollback(campaignId, userId, completion, duplicate);
        } catch (CoreException failure) {
            if (failure.getErrorType() != ErrorType.COUPON_SOLD_OUT
                    && failure.getErrorType() != ErrorType.COUPON_CAMPAIGN_INACTIVE) {
                throw failure;
            }
            if (failure.getErrorType() == ErrorType.COUPON_SOLD_OUT
                    && completion.outcome() == Outcome.ROLLED_BACK) {
                completion.soldOutDetected = true;
            }
            return recoverAfterRollback(campaignId, userId, completion, failure);
        }
    }

    private IssueResult issueLocked(long campaignId, long userId, Completion completion) {
        reportCompletion(completion);

        CouponCampaign campaign = campaigns.lockById(campaignId)
                .orElseThrow(() -> new CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));

        if (!campaign.active()) {
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE);
        }
        if (campaign.issuedCount() >= campaign.totalQuota()) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        }

        campaigns.increaseIssuedCount(campaignId);
        Coupon issued = coupons.insert(campaignId, userId, campaign.benefitDescription());
        return new IssueResult(issued, true);
    }

    private IssueResult recoverAfterRollback(long campaignId, long userId, Completion completion,
                                              RuntimeException failure) {
        if (completion.outcome() != Outcome.ROLLED_BACK) {
            throw failure;
        }
        completion.recoveryInProgress = true;
        var existing = lookup(campaignId, userId, completion, false);
        completion.recoveryInProgress = false;
        return new IssueResult(existing.orElseThrow(() -> failure), false);
    }

    private Optional<Coupon> lookup(long campaignId, long userId, Completion completion, boolean reportRead) {
        return transaction(true).execute(status -> {
            if (reportRead) {
                reportCompletion(completion);
            }
            return coupons.findByCampaignAndUser(campaignId, userId);
        });
    }

    private TransactionTemplate transaction(boolean readOnly) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(readOnly);
        return transaction;
    }

    private void reportCompletion(Completion completion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                completion.complete(status);
            }
        });
    }
}
