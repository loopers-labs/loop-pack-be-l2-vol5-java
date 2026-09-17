package com.loopers.domain.common;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 목록 조회의 페이지 입력. [잠정] 기본 page=0, size=20, 최대 size=100.
 */
public record PageCommand(int page, int size) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public static PageCommand of(Integer page, Integer size) {
        int resolvedPage = page != null ? page : DEFAULT_PAGE;
        int resolvedSize = size != null ? size : DEFAULT_SIZE;

        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new CoreException(ErrorType.INVALID_PAGE_REQUEST);
        }
        return new PageCommand(resolvedPage, resolvedSize);
    }
}
