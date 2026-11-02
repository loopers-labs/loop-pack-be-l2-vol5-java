package com.loopers.application.purchase;

import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

/**
 * 예외가 "unique에 걸렸다"인지 가린다. 같은 DataIntegrityViolationException이라도 NOT NULL 위반 같은 다른 무결성 실패는
 * 다시 해도 똑같이 터지는 코드 문제라, 비교하지 않고 멈춤으로 보낸다(docs/week6/decisions.md D-29).
 */
final class DuplicateKeys {

    /** MySQL ER_DUP_ENTRY. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private DuplicateKeys() {}

    static boolean isDuplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
