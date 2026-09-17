package com.loopers.domain.common;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;

public record PageCondition(int page, int size) {

    private static final int MAX_SIZE = 100;

    public PageCondition {
        if (page < 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "size는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
