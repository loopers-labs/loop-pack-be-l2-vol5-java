package com.loopers.domain.user;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    USER_NOT_FOUND("존재하지 않는 사용자입니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
