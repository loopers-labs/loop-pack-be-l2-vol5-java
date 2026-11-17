package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.coupon.support.CouponSlotRetryTiming;
import com.loopers.application.coupon.usecase.IssueCouponUseCase;
import com.loopers.application.coupon.support.CouponAdmissionException.Code;
import com.loopers.application.coupon.support.CouponAdmissionException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class IssueCouponFacade implements IssueCouponUseCase {
    private final CouponIssueLookupService lookup;
    private final IssueCouponService issuance;
    private final CouponAdmissionControl control;
    private final CouponSlotRetryTiming timing;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResult execute(CouponIssueCommand command) {
        Optional<CouponIssueResult> existing = lookup.findExisting(command);
        if (existing.isPresent()) {
            return existing.get();
        }
        CouponAdmissionControl.Handle handle = control.acquire(command);
        boolean uncertain = false;
        try {
            return issueWithSlotRechecks(command, handle);
        } catch (TransactionSystemException | CannotCreateTransactionException failure) {
            uncertain = true;
            throw failure;
        } finally {
            if (!control.finish(handle, uncertain)) {
                throw new CouponAdmissionException(Code.COUPON_ISSUE_OUTCOME_UNKNOWN);
            }
        }
    }

    private CouponIssueResult issueWithSlotRechecks(CouponIssueCommand command, CouponAdmissionControl.Handle handle) {
        long started = timing.now();
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                control.authorizeRetry(handle);
                if (timing.now() - started >= TimeUnit.MILLISECONDS.toNanos(200)) {
                    throw new CouponSlotBusyException();
                }
            }
            try {
                return issuance.execute(command, handle.execution());
            } catch (CouponSlotBusyException busy) {
                if (!handle.execution().transactionsConfirmed()) {
                    throw new CouponAdmissionException(Code.COUPON_ISSUE_OUTCOME_UNKNOWN);
                }
                if (attempt >= 3 || timing.now() - started >= TimeUnit.MILLISECONDS.toNanos(150)) {
                    throw busy;
                }
                try {
                    timing.pause();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
                if (timing.now() - started >= TimeUnit.MILLISECONDS.toNanos(200)) {
                    throw busy;
                }
            }
        }
    }
}
