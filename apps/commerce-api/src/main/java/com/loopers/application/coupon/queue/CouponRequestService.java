package com.loopers.application.coupon.queue;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponRequestService {
    private final IdentifyUser users;
    private final CouponRequestQueue queue;
    private final CouponRepository coupons;
    private final CouponQueueWorkerState state;

    public sealed interface Submission permits ExistingCoupon, QueuedRequest {
    }

    public record ExistingCoupon(long couponId) implements Submission {
    }

    public record QueuedRequest() implements Submission {
    }

    public Submission submit(Long requester, long campaignId) {
        validateCampaign(campaignId);
        long userId = requireUser(requester);
        final Optional<Coupon> existingCoupon;
        try {
            existingCoupon = coupons.findByCampaignAndUser(campaignId, userId);
        } catch (DataAccessException | TransactionException failure) {
            state.pause();
            log.warn("Coupon request existing coupon lookup unavailable", failure);
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        if (existingCoupon.isPresent()) {
            return new ExistingCoupon(existingCoupon.get().couponId());
        }
        var result = queue.submit(campaignId, userId);
        return switch (result.outcome()) {
            case ACCEPTED, EXISTING -> new QueuedRequest();
            case FULL -> throw new CoreException(ErrorType.COUPON_ADMISSION_FULL);
            case PAUSED -> throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            case SOLD_OUT -> throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        };
    }

    private long requireUser(Long requester) {
        try {
            return users.require(requester);
        } catch (DataAccessException | TransactionException failure) {
            state.pause();
            log.warn("Coupon request user lookup unavailable", failure);
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    private void validateCampaign(long campaignId) {
        if (campaignId <= 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
