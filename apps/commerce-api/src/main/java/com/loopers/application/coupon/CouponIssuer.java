package com.loopers.application.coupon;

import com.loopers.domain.coupon.IssuedCouponDuplicateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DB 발급 경로의 입구(docs/week8/decisions.md D-8, D-12). 동시 처리 자리(Redis)를 잡은 뒤 이 빈을 부른다.
 *
 * <p>TX는 두 개로 나뉜다. 같은 빈 안의 호출은 트랜잭션 프록시를 거치지 않으므로 각각 다른 빈이다.
 * <ol>
 *   <li>{@link CouponIssueTransaction} — 캠페인 락, 판단, 발급 수 증가, 쿠폰 insert를 한 TX로.</li>
 *   <li>그 TX가 (campaign_id, user_id) unique에 걸려 rollback됐을 때만, 새 TX({@link CouponQuery})에서 기존 쿠폰을 조회해
 *       EXISTING으로 돌려준다(INV-802). 다시 조회해도 없으면 원래 예외를 그대로 던진다.</li>
 * </ol>
 * 이 unique 위반이 아닌 오류(다른 제약, 연결 오류)와 품절·비활성(409)·없는 캠페인(404)은 바꾸지 않고 그대로 나간다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssuer {

    private final CouponIssueTransaction couponIssueTransaction;
    private final CouponQuery couponQuery;

    public CouponIssueResult issue(Long campaignId, Long userId) {
        try {
            return couponIssueTransaction.issue(campaignId, userId);
        } catch (IssuedCouponDuplicateException e) {
            IssuedCouponInfo existing = couponQuery.findIssued(campaignId, userId).orElseThrow(() -> e);
            log.info("쿠폰 발급: unique 위반으로 rollback 뒤 기존 쿠폰 반환 campaignId={}, userId={}, couponId={}",
                campaignId, userId, existing.couponId());
            return CouponIssueResult.existing(existing);
        }
    }
}
