package com.loopers.fixture.pg;

public enum ReplyMode {
    NORMAL,
    RAW,
    DELAY,
    /** 실제보다 긴 Content-Length 를 선언하고 본문 일부만 보낸 뒤 연결을 닫는다. */
    CLOSE_EARLY,
    GATE
}
