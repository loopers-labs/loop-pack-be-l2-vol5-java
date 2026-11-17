package com.loopers.infrastructure.persistence.coupon.support;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

public final class CouponSqlFailures {
    private CouponSqlFailures() {}

    public static boolean isUserDuplicate(Throwable failure) {
        boolean duplicateCode = false;
        boolean userConstraint = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                duplicateCode = true;
            }
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName();
                userConstraint |= name.equals("uk_coupons_campaign_user") || name.endsWith(".uk_coupons_campaign_user");
            }
        }
        return duplicateCode && userConstraint;
    }

    public static boolean isLockWaitTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1205) {
                return true;
            }
        }
        return false;
    }
}
