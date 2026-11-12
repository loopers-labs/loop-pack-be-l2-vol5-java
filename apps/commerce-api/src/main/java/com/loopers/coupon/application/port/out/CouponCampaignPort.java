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

    /** 마감 시각이 from 이후 to 이전(포함)인 캠페인 — 마감 뒤 Redis 정리 대상 (D-4-6) */
    List<CouponCampaign> findEndedBetween(ZonedDateTime from, ZonedDateTime to);
}
