package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAlreadyIssuedException;
import com.loopers.domain.coupon.CouponRepository;

import lombok.RequiredArgsConstructor;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {
    private static final String USER_CAMPAIGN_CONSTRAINT = "uk_coupon_campaign_user";
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final Pattern MYSQL_KEY = Pattern.compile("\\bfor key ['\"`]([^'\"`]+)['\"`]", Pattern.CASE_INSENSITIVE);

    private final CouponJpaRepository coupons;

    @Override
    @Transactional(readOnly = true)
    public Optional<Coupon> findByCampaignAndUser(long campaignId, long userId) {
        return coupons.findByCampaignIdAndUserId(campaignId, userId).map(CouponJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findByUser(long userId) {
        return coupons.findByUserIdOrderByIssuedAtDescIdDesc(userId).stream()
                .map(CouponJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Coupon insert(long campaignId, long userId, String benefitDescription) {
        try {
            return coupons.saveAndFlush(new CouponJpaEntity(campaignId, userId, benefitDescription)).toDomain();
        } catch (DataIntegrityViolationException error) {
            if (isUserCampaignDuplicate(error)) {
                throw new CouponAlreadyIssuedException(error);
            }
            throw error;
        }
    }

    private boolean isUserCampaignDuplicate(DataIntegrityViolationException error) {
        String constraintName = null;
        boolean duplicateKey = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                constraintName = violation.getConstraintName();
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                duplicateKey = true;
                if (constraintName == null && sql.getMessage() != null) {
                    Matcher matcher = MYSQL_KEY.matcher(sql.getMessage());
                    if (matcher.find()) {
                        constraintName = matcher.group(1);
                    }
                }
            }
        }
        return duplicateKey && sameConstraint(constraintName);
    }

    private boolean sameConstraint(String name) {
        if (name == null) {
            return false;
        }
        // MySQL은 'table.index' 또는 `schema`.`table`.`index` 형태로도 반환한다.
        String unquoted = name.replace("`", "").replace("'", "").replace("\"", "").trim();
        int dot = unquoted.lastIndexOf('.');
        String lastPart = dot < 0 ? unquoted : unquoted.substring(dot + 1);
        return USER_CAMPAIGN_CONSTRAINT.equalsIgnoreCase(lastPart);
    }
}
