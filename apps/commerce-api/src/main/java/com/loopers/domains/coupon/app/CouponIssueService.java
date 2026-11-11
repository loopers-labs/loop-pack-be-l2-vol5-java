package com.loopers.domains.coupon.app;

import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.app.port.DuplicateIssueException;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 한정 수량 쿠폰 발급(29절). ① 잠금 없는 조회로 대부분을 끝내고, ④ 짧은 트랜잭션 하나에서 조건부 UPDATE와 INSERT를 한다.
// 발급 판단은 DB만 쓴다(JVM 메모리 상태 없음). 2·3단계의 수용(②③)·정리(⑤)는 lookup 다음과 issueInTransaction 앞뒤에 들어간다.
@Service
public class CouponIssueService {
    // 품절·비활성은 409 하나로 묶고 메시지도 하나다(D126).
    static final String NOT_ISSUABLE = "쿠폰을 발급할 수 없습니다. 캠페인이 비활성이거나 수량이 모두 발급되었습니다.";

    public record IssueResult(IssuedCouponView coupon, boolean newlyIssued) { }

    private final CouponRepository couponRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CouponIssueService(CouponRepository couponRepository, TransactionTemplate transactionTemplate, Clock clock) {
        this.couponRepository = couponRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // 판정 순서: 캠페인 없음(404) → 이미 받은 쿠폰(200, 비활성·품절이어도) → 발급 불가(409) → 발급.
    public IssueResult issue(long campaignId, long userId) {
        CouponIssueLookup lookup = lookup(campaignId, userId);
        if (lookup.coupon().isPresent()) {
            return new IssueResult(IssuedCouponView.from(lookup.coupon().get()), false);
        }
        if (!lookup.campaign().canIssue()) {
            throw new RuleViolationException(NOT_ISSUABLE);
        }
        return issueInTransaction(campaignId, userId);
    }

    public List<IssuedCouponView> listMine(long userId) {
        return couponRepository.findByUser(userId).stream().map(IssuedCouponView::from).toList();
    }

    private CouponIssueLookup lookup(long campaignId, long userId) {
        return couponRepository.findForIssue(campaignId, userId)
            .orElseThrow(() -> new CouponCampaignNotFoundException(campaignId));
    }

    // 조건부 UPDATE가 0행이면 409다(①과 ④ 사이에 다른 요청이 마지막 수량을 가져갔다).
    // 같은 사용자의 동시 요청은 유일 제약이 하나만 통과시킨다. 충돌한 쪽은 그 트랜잭션이 UPDATE까지 rollback된 뒤,
    // 트랜잭션 밖에서 다시 조회해 쿠폰이 있을 때만 200으로 바꾼다. 없으면 원래 예외를 그대로 올린다.
    private IssueResult issueInTransaction(long campaignId, long userId) {
        try {
            IssuedCoupon issued = transactionTemplate.execute(status -> {
                if (couponRepository.increaseIssued(campaignId) == 0) {
                    throw new RuleViolationException(NOT_ISSUABLE);
                }
                return couponRepository.insert(campaignId, userId, clock.instant());
            });
            return new IssueResult(IssuedCouponView.from(issued), true);
        } catch (DuplicateIssueException e) {
            Optional<IssuedCoupon> existing = couponRepository.findForIssue(campaignId, userId)
                .flatMap(CouponIssueLookup::coupon);
            if (existing.isPresent()) {
                return new IssueResult(IssuedCouponView.from(existing.get()), false);
            }
            throw e;
        }
    }
}
