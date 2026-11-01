package com.loopers.pgsimulator;

/**
 * 알림 1건의 전송 결과. sent=false 는 종료로 전송을 시작하지 못한 경우다. httpStatus 와 failure 중 하나만 있다.
 * HTTP 200 도 그 자체로 결제 성공의 증거는 아니다(5.7).
 */
public record CallbackResult(boolean sent, Integer httpStatus, CallbackFailure failure, String errorDetail) {
}
