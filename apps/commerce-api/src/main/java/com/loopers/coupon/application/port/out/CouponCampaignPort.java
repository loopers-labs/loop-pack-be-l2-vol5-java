package com.loopers.coupon.application.port.out;

import com.loopers.coupon.domain.CouponCampaign;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponCampaignPort {

    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long id);

    /**
     * 발급 기간 안(시작 ≤ now &lt; 마감)인 캠페인. 서버마다 주기적으로 읽어 메모리에 둔다 (D-4-3 열린 캠페인 목록).
     */
    List<CouponCampaign> findOpenAt(ZonedDateTime now);
}
