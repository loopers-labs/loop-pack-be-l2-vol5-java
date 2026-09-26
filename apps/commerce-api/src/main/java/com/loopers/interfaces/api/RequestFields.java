package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

/**
 * 요청 본문의 필수 필드를 확인한다. 값의 범위는 domain이 확인한다.
 */
public final class RequestFields {

    private RequestFields() {
    }

    public static <T> T required(T value) {
        if (value == null) {
            throw new CoreException(ErrorCode.INVALID_REQUEST);
        }
        return value;
    }
}
