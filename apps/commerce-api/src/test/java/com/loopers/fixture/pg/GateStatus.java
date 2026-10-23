package com.loopers.fixture.pg;

public enum GateStatus {
    /** 등록만 됐고 아직 해당 요청이 도착하지 않음 */
    REGISTERED,
    WAITING,
    RELEASED,
    /** 해제 없이 최대 대기를 넘겨 응답 없이 연결을 닫음 */
    TIMED_OUT
}
