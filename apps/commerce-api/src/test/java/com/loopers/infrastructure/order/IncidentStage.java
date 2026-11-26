package com.loopers.infrastructure.order;

import java.util.Locale;

/** W10 사고 시나리오 단계. 증거 디렉터리 이름은 소문자 단계명이다. */
enum IncidentStage {
    BASELINE,
    FAULT,
    RECOVERY;

    String directoryName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
