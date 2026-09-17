package com.loopers.interfaces.api.support;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 목록 페이지 입력 (P-08). page ≥ 0 (기본 0), size 1–100 (기본 20), 범위 밖이면 400.
 */
public record PageQuery(int page, int size) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "size는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        return new PageQuery(resolvedPage, resolvedSize);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
