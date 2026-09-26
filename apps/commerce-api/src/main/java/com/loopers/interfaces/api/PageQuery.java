package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

/**
 * 목록 요청의 페이지 조건이다. page는 0 이상, size는 1~100이어야 한다.
 */
public record PageQuery(int page, int size) {

    public static final String DEFAULT_PAGE = "0";
    public static final String DEFAULT_SIZE = "20";

    private static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new CoreException(ErrorCode.INVALID_REQUEST);
        }
    }
}
