package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 쿠폰 발급의 DB 구간 (8주차 설계 §5-3 3번, §6).
 * 기존 조회 → 조건부 UPDATE(총량, INV-W8-02) → INSERT(UNIQUE, INV-W8-03) 를 한 트랜잭션으로 묶는다 (INV-W8-08).
 *
 * UNIQUE 위반은 트랜잭션이 rollback 으로 끝난 뒤에 잡는다. 같은 사용자의 동시 요청에서 패자는 issued_count 를 이미 올린 상태라,
 * 트랜잭션 안에서 잡아 기존 쿠폰을 돌려주면 증가분이 남아 총량을 1 잃는다. 그래서 TX 경계를 메서드 안(TransactionTemplate)에 둔다.
 * Redis 수용 통제와 무관하게 이 클래스만으로 총량·사용자당 1장이 성립해야 한다 (§1).
 */
@Component
public class CouponIssueService {

    /**
     * @param coupon  발급됐거나 이미 있던 쿠폰
     * @param created true 면 이번 호출로 새로 발급됐다 (201). false 면 기존 쿠폰이다 (200)
     */
    public record IssueResult(Coupon coupon, boolean created) {}

    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponRepository couponRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CouponIssueService(
        CouponCampaignRepository couponCampaignRepository,
        CouponRepository couponRepository,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponRepository = couponRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public IssueResult issue(Long campaignId, Long userId) {
        try {
            return transactionTemplate.execute(status -> issueInTransaction(campaignId, userId));
        } catch (DataIntegrityViolationException e) {
            if (!isCampaignUserDuplicate(e)) {
                throw e;
            }
            return couponRepository.findByCampaignIdAndUserId(campaignId, userId)
                .map(existing -> new IssueResult(existing, false))
                .orElseThrow(() -> e);
        }
    }

    private IssueResult issueInTransaction(Long campaignId, Long userId) {
        CouponCampaign campaign = couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "존재하지 않는 캠페인입니다."));
        if (!campaign.isActive()) {
            throw new CoreException(ErrorType.CONFLICT, "종료된 캠페인입니다.");
        }
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return new IssueResult(existing.get(), false);
        }
        if (couponCampaignRepository.increaseIssuedCountIfAvailable(campaignId) == 0) {
            throw new CoreException(ErrorType.CONFLICT, "쿠폰이 모두 소진되었습니다.");
        }
        Coupon saved = couponRepository.save(new Coupon(campaign, userId, ZonedDateTime.now(clock)));
        return new IssueResult(saved, true);
    }

    /**
     * 위반된 제약이 (campaign_id, user_id) UNIQUE 인지 확인한다. 다른 제약·연결 오류를 "이미 발급됨"으로 바꾸면 장애를 숨긴다 (§6).
     * MySQL 8 은 제약 이름을 'coupon.uk_coupon_campaign_user' 처럼 테이블 접두를 붙여 돌려주므로 endsWith 로 비교한다.
     * 접두 유무가 드라이버·버전에 따라 달라도 안전하고, 이 프로젝트의 제약 이름 규칙(uk_<테이블>_<컬럼>)상 꼬리가 겹치는 이름이 없다.
     */
    private static boolean isCampaignUserDuplicate(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name != null && name.endsWith(Coupon.UK_CAMPAIGN_USER);
            }
            cause = cause.getCause();
        }
        return false;
    }
}
