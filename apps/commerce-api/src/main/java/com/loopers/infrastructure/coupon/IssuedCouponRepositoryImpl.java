package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.IssuedCoupon;
import com.loopers.domain.coupon.IssuedCouponDuplicateException;
import com.loopers.domain.coupon.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class IssuedCouponRepositoryImpl implements IssuedCouponRepository {

    private static final String UK_NAME = IssuedCoupon.UK_CAMPAIGN_USER.toLowerCase(Locale.ROOT);

    private final IssuedCouponJpaRepository issuedCouponJpaRepository;

    /**
     * (campaign_id, user_id) unique 위반만 {@link IssuedCouponDuplicateException}으로 바꾼다(docs/week8/decisions.md D-8).
     * 다른 무결성 위반은 성공(기존 쿠폰)으로 바뀌면 안 되므로 원래 예외 그대로 던진다.
     */
    @Override
    public IssuedCoupon save(IssuedCoupon coupon) {
        try {
            return issuedCouponJpaRepository.saveAndFlush(coupon);
        } catch (DataIntegrityViolationException e) {
            if (isCampaignUserUniqueViolation(e)) {
                throw new IssuedCouponDuplicateException(coupon.getCampaignId(), coupon.getUserId(), e);
            }
            throw e;
        }
    }

    @Override
    public Optional<IssuedCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return issuedCouponJpaRepository.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public List<IssuedCoupon> findAllByUserId(Long userId) {
        return issuedCouponJpaRepository.findAllByUserIdOrderByIssuedAtDescIdDesc(userId);
    }

    // Hibernate가 MySQL 메시지("Duplicate entry … for key 'issued_coupon.uk_…'")에서 뽑은 제약 이름으로 가린다.
    // MySQL 8은 이름 앞에 테이블명을 붙이므로 끝부분으로 비교한다.
    private static boolean isCampaignUserUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().toLowerCase(Locale.ROOT);
                return name.equals(UK_NAME) || name.endsWith("." + UK_NAME);
            }
        }
        return false;
    }
}
