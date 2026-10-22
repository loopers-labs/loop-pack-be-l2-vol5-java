package com.loopers.infrastructure.support;

import java.sql.SQLException;
import java.util.regex.Pattern;

public final class MySqlConstraintErrors {
    private MySqlConstraintErrors() {}

    public static boolean isDuplicateKey(Throwable failure, String constraintName) {
        Pattern key = Pattern.compile("for key ['`](?:[^'`]+\\.)?" + Pattern.quote(constraintName) + "['`]\\s*$");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062
                && "23000".equals(sql.getSQLState()) && sql.getMessage() != null
                && key.matcher(sql.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }
}
