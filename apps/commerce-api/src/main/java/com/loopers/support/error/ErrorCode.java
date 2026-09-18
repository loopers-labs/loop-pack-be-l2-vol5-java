package com.loopers.support.error;

/**
 * 업무 오류 코드. HTTP 상태를 알지 않으며, 상태로 바꾸는 일은 interfaces 에서 한다.
 */
public interface ErrorCode {
    String getCode();

    String getMessage();
}
