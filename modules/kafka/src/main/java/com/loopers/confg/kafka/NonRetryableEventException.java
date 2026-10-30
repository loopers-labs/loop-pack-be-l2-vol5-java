package com.loopers.confg.kafka;

/**
 * 재시도해도 같은 결과가 나오는 소비 실패. record listener의 error handler가 재시도 없이 컨테이너를 멈춘다.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }
}
