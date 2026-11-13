package com.loopers.infrastructure.coupon;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

final class DuplicateKey {

    private static final int MYSQL_DUPLICATE_KEY = 1062;

    private DuplicateKey() {
    }

    static boolean on(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                return sql.getMessage() != null && sql.getMessage().contains(constraint);
            }
        }
        return false;
    }
}
