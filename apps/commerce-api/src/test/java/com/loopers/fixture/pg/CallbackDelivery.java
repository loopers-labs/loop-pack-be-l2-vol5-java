package com.loopers.fixture.pg;

/**
 * 알림 1건의 전송 결과. attempt 는 호출 안의 1부터의 번호이며 수신·완료 순서가 아니다.
 * sent=false 는 종료로 전송을 시작하지 못한 경우다. httpStatus 와 failure 중 하나만 있다.
 */
public record CallbackDelivery(int attempt, boolean sent, Integer httpStatus, CallbackFailure failure, String errorDetail) {
}
