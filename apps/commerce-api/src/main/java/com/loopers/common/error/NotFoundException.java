package com.loopers.common.error;

// 찾는 대상이 없다는 업무 신호. HTTP 상태로 바꾸는 일은 common.in의 advice만 한다.
public abstract class NotFoundException extends RuntimeException {
    protected NotFoundException(String message) {
        super(message);
    }
}
