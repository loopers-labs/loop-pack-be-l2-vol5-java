package com.loopers.support.error;

import org.hibernate.exception.ConstraintViolationException;

public final class DatabaseConstraintErrors {
    private DatabaseConstraintErrors() {
    }

    public static boolean isDuplicateKey(Throwable failure, String expectedConstraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && violation.getErrorCode() == 1062 && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "");
                if (name.equals(expectedConstraint) || name.endsWith("." + expectedConstraint)) {
                    return true;
                }
            }
        }
        return false;
    }
}
