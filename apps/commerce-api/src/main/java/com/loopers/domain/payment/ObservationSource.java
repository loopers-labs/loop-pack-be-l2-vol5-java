package com.loopers.domain.payment;

/** 관찰을 얻은 경로. 종결 뒤 늦은 관찰의 처리(3.6)가 출처에 따라 다르다. */
public enum ObservationSource {
    CREATE,
    QUERY
}
