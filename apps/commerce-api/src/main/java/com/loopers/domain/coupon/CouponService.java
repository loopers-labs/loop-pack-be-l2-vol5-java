package com.loopers.domain.coupon;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponService {

    private static final String TICKETED = "TICKETED";

    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueTicketRepository couponIssueTicketRepository;

    /**
     * 쿠폰을 저장하고 발급 수를 올린다. 트랜잭션은 호출하는 쪽이 연다.
     *
     * <p>insert 를 먼저 해 같은 사용자 쿠폰이 있으면 유일 제약 충돌이 먼저 드러나게 한다. 발급 수가 이미 한도면
     * {@code COUPON_SOLD_OUT} 을 던져 넣은 쿠폰까지 rollback 되게 한다.
     */
    public Coupon issue(Long campaignId, Long userId, ZonedDateTime now) {
        CouponCampaign campaign = couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        Coupon coupon = couponRepository.insert(Coupon.issue(campaign, userId, now));
        if (couponCampaignRepository.increaseIssuedCount(campaignId) == 0) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        }
        return coupon;
    }

    public Optional<Coupon> findIssued(Long campaignId, Long userId) {
        return couponRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    /** 202 로 약속한 당첨을 기록한다. 같은 당첨의 기록이 이미 있으면 유일 제약 충돌이 난다. */
    public CouponIssueTicket recordTicket(Long campaignId, Long userId, ZonedDateTime now) {
        return couponIssueTicketRepository.insert(CouponIssueTicket.record(campaignId, userId, now));
    }

    public Page<Coupon> getList(Long userId, Pageable pageable) {
        return couponRepository.findAllByUserId(userId, pageable);
    }

    public CouponCampaign prepare(String name, Money discountAmount, int totalQuota) {
        return couponCampaignRepository.save(CouponCampaign.create(name, discountAmount, totalQuota));
    }

    public CouponCampaign getCampaign(Long campaignId) {
        return couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
    }

    /** 신규 발급을 끝낸다. 호출하는 쪽의 트랜잭션 안에서 변경 감지로 저장된다. */
    public void close(Long campaignId) {
        getCampaign(campaignId).close();
    }

    /**
     * DB 에 남은 약속으로 당첨자 명단을 만든다. 쿠폰이 있으면 couponId, 쿠폰 없는 당첨 기록이면 TICKETED 다.
     * 약속하지 않은 당첨(Redis 에만 있던 PENDING)은 원본이 없으므로 포함되지 않는다.
     */
    public Map<Long, String> promisedWinners(Long campaignId) {
        Map<Long, String> winners = new HashMap<>();
        couponIssueTicketRepository.findAllByCampaignId(campaignId)
            .forEach(ticket -> winners.put(ticket.getUserId(), TICKETED));
        couponRepository.findAllByCampaignId(campaignId)
            .forEach(coupon -> winners.put(coupon.getUserId(), String.valueOf(coupon.getId())));
        return winners;
    }

    public boolean campaignExists(Long campaignId) {
        return couponCampaignRepository.findById(campaignId).isPresent();
    }
}
