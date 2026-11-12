package com.loopers.coupon.adapter.out.persistence;

import com.loopers.coupon.application.port.out.CouponCampaignPort;
import com.loopers.coupon.application.port.out.CouponPort;
import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponPersistenceAdapter implements CouponCampaignPort, CouponPort {

    private final CouponCampaignJpaRepository campaignJpaRepository;
    private final CouponJpaRepository couponJpaRepository;

    /**
     * 즉시 flush해 생성 시각이 응답을 만들기 전에 반영되게 한다 (상품과 같은 이유).
     */
    @Override
    public CouponCampaign save(CouponCampaign campaign) {
        return campaignJpaRepository.saveAndFlush(campaign);
    }

    @Override
    public Optional<CouponCampaign> findById(Long id) {
        return campaignJpaRepository.findById(id).filter(campaign -> campaign.getDeletedAt() == null);
    }

    @Override
    public List<CouponCampaign> findOpenAt(ZonedDateTime now) {
        return campaignJpaRepository.findOpenAt(now);
    }

    @Override
    public List<CouponCampaign> findEndedBetween(ZonedDateTime from, ZonedDateTime to) {
        return campaignJpaRepository.findEndedBetween(from, to);
    }

    @Override
    public List<Coupon> saveAll(List<Coupon> coupons) {
        List<Coupon> saved = couponJpaRepository.saveAll(coupons);
        couponJpaRepository.flush();
        return saved;
    }

    @Override
    public List<Coupon> findByCampaignId(Long campaignId) {
        return couponJpaRepository.findByCampaignIdOrderByIdAsc(campaignId);
    }

    @Override
    public List<Coupon> findAllByIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : couponJpaRepository.findAllById(ids);
    }
}
