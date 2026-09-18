package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 외부 계약의 페이지(1부터)를 내부 표현(0부터)으로 바꾸는 한 곳. 잘못된 값은 기본값으로 바꾸지 않고 거절한다 (설계 6.1).
 */
public final class PageQuery {
    public static final int MAX_SIZE = 100;

    private PageQuery() {}

    public static Pageable of(int page, int size) {
        if (page < 1) {
            throw new CoreException(ErrorType.BAD_REQUEST, "page 는 1 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "size 는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        return PageRequest.of(page - 1, size);
    }
}
