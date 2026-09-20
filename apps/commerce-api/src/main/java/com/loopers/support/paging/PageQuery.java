package com.loopers.support.paging;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 오프셋 페이징 요청 (설계 4-1, DR-19). page 는 0~100, size 는 1~100.
 * 검증 실패는 ER-08 INVALID_PAGE.
 */
public record PageQuery(int page, int size) {
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_PAGE = 100;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0 || page > MAX_PAGE) {
            throw new CoreException(ErrorType.INVALID_PAGE, "page 는 0~" + MAX_PAGE + " 사이여야 합니다. [page = " + page + "]");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new CoreException(ErrorType.INVALID_PAGE, "size 는 1~" + MAX_SIZE + " 사이여야 합니다. [size = " + size + "]");
        }
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? DEFAULT_PAGE : page, size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) page * size;
    }
}
