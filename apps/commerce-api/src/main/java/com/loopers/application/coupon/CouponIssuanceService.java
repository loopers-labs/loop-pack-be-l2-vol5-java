package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssuanceModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssuanceRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssuanceService {

    private final CouponCampaignRepository campaignRepository;
    private final CouponIssuanceRepository issuanceRepository;

    @Transactional
    public CouponIssuanceModel issue(long campaignId, long userId) {
        int updated = campaignRepository.incrementIssuedCountIfAvailable(campaignId);
        if (updated == 0) {
            throw new CoreException(ErrorType.CONFLICT, "쿠폰 수량이 소진되었습니다.");
        }
        return issuanceRepository.save(new CouponIssuanceModel(campaignId, userId));
    }
}
