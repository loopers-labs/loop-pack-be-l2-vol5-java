package com.loopers.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public final class PagePolicy {
    private static final int MAX_PAGE_SIZE = 100;

    private PagePolicy() {
    }

    public static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
