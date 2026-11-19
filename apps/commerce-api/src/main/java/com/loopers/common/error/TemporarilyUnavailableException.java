package com.loopers.common.error;

// 지금은 결과를 확인할 수 없어 잠시 뒤 다시 시도해야 한다는 업무 신호. HTTP 상태로 바꾸는 일은 common.in의 advice만 한다.
public abstract class TemporarilyUnavailableException extends RuntimeException {
    protected TemporarilyUnavailableException(String message) {
        super(message);
    }

    protected TemporarilyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
