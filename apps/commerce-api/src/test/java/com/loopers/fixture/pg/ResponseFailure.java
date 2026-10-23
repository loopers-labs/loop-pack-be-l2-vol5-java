package com.loopers.fixture.pg;

/** 응답을 정상적으로 끝까지 보내지 못한 이유. 거래 효과의 유무와는 별개다. */
public enum ResponseFailure {
    CLOSED_EARLY,
    GATE_TIMEOUT,
    ABORTED_BY_CLOSE,
    IO_ERROR
}
