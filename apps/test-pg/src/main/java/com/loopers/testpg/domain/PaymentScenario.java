package com.loopers.testpg.domain;

public enum PaymentScenario {
    SUCCESS,
    FAIL,
    REJECT_BEFORE_CREATE,
    DROP_RESPONSE,
    SKIP_CALLBACK,
    DUPLICATE_CALLBACK,
    CREATE_RESPONSE_OVERRIDE
}
