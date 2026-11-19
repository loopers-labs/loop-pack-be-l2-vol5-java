package com.loopers.common.error;

// 지금 받을 수 있는 자리가 없다는 업무 신호. 다시 요청해도 결과를 보장하지 않는다. HTTP 상태로 바꾸는 일은 common.in의 advice만 한다.
public class CapacityExceededException extends RuntimeException {
    public CapacityExceededException(String message) {
        super(message);
    }
}
