package com.loopers.application.coupon.service;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponDatabaseClock;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssueLookupService {
    private final CouponRepository couponRepository;
    private final CouponCampaignRepository campaignRepository;
    private final CouponDatabaseClock databaseClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<CouponIssueResult> findExisting(CouponIssueCommand command) {
        return couponRepository.findByCampaignIdAndUserId(command.campaignId(), command.userId())
            .map(coupon -> CouponIssueResult.from(coupon, false));
    }

    /** 빈 슬롯 TX가 끝난 뒤 새 컨텍스트에서 기존 결과/업무 거절과 일시 경합을 구분한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.READ_COMMITTED)
    public CouponIssueResult resolveUnavailableSlot(CouponIssueCommand command) {
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(command.campaignId(), command.userId());
        if (existing.isPresent()) {
            return CouponIssueResult.from(existing.get(), false);
        }
        CouponCampaign current = campaignRepository.findById(command.campaignId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.COUPON_CAMPAIGN_NOT_FOUND));
        current.ensureIssuable(databaseClock.now());
        throw new CouponSlotBusyException();
    }
}
