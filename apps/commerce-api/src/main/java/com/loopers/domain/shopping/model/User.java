package com.loopers.domain.shopping.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 사용자 도메인 모델
public final class User {
    private final long id;

    private User(long id) {
        if (id <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_USER_ID);
        }
        this.id = id;
    }

    // 신규 사용자 생성
    public static User create(long id) {
        return new User(id);
    }

    // 저장된 사용자 복원
    public static User restore(long id) {
        return new User(id);
    }

    public long getId() {
        return id;
    }
}
