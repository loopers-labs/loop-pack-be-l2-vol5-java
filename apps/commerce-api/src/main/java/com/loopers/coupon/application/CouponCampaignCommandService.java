package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponCampaignAdminInfo;
import com.loopers.coupon.application.port.in.CouponCampaignCommandUseCase;
import com.loopers.coupon.application.port.out.CouponCampaignPort;
import com.loopers.coupon.application.port.out.CouponPort;
import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponDraft;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 캠페인과 쿠폰들을 한 트랜잭션에서 만든다 (D-5 쿠폰팩, CPN-01–06). 하나라도 거절되면 아무것도 저장하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class CouponCampaignCommandService implements CouponCampaignCommandUseCase {

    private final CouponCampaignPort couponCampaignPort;
    private final CouponPort couponPort;
    private final ProductPort productPort;

    @Transactional
    @Override
    public CouponCampaignAdminInfo create(CreateCampaignCommand command) {
        if (command.coupons() == null || command.coupons().isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인에는 쿠폰이 1장 이상 있어야 합니다.");
        }
        CouponCampaign campaign = couponCampaignPort.save(new CouponCampaign(
            command.name(), command.totalQuantity(), command.queueCapacity(), command.issueStartAt(), command.issueEndAt()));
        List<Coupon> coupons = command.coupons().stream()
            .map(draft -> Coupon.of(campaign.getId(), draft))
            .toList();
        requireSellableProducts(command.coupons());
        return CouponCampaignAdminInfo.of(campaign, couponPort.saveAll(coupons));
    }

    /**
     * CPN-05: 적용 상품은 모두 살아 있는 상품이어야 한다. 없거나 삭제된 상품이 있으면 404.
     */
    private void requireSellableProducts(List<CouponDraft> drafts) {
        Set<Long> productIds = drafts.stream()
            .flatMap(draft -> draft.applicableProductIds().stream())
            .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return;
        }
        Set<Long> sellable = productPort.findAllByIds(productIds).stream()
            .filter(ProductModel::isSellable)
            .map(ProductModel::getId)
            .collect(Collectors.toSet());
        Set<Long> missing = new HashSet<>(productIds);
        missing.removeAll(sellable);
        if (!missing.isEmpty()) {
            throw new CoreException(ErrorType.NOT_FOUND, "적용 상품을 찾을 수 없습니다: " + missing);
        }
    }
}
