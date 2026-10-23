package com.loopers.domain.payment;

/** 생성 POST 가 확실히 나가지 않았다는 원인별 증거(2.8). 일반 timeout·원인 불명 오류는 여기에 넣지 않는다. */
public enum LocalNotDispatchedReason {
    CIRCUIT_REJECTED,
    POOL_ACQUIRE_TIMEOUT
}
