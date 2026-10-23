package com.loopers.fixture.pg;

/**
 * 콜백 전송의 통신 결과. 커머스가 받지 못했는지·처리 중인지·이미 반영했는지는 알 수 없는 관측이며
 * 결제 실패·재고 반환·재전송의 근거가 아니다(5.7).
 */
public enum CallbackFailure {
    CONNECT_FAILED,
    /** 연결·응답 timeout 또는 전용 풀 대기 초과 */
    TIMEOUT,
    IO_ERROR,
    /** fixture 종료로 보내지 못했거나 전송 중 중단됨 */
    ABORTED_BY_CLOSE
}
