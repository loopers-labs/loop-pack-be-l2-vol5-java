package com.loopers.fixture.pg;

public enum CreateOutcome {
    PENDING,
    SUCCESS,
    /** 거래 저장·후속 작업 없이 5.5 의 정확한 422 를 반환한다. 효과 0건 증거이므로 reply 는 NORMAL 만 허용한다. */
    REJECT_BEFORE_CREATE
}
