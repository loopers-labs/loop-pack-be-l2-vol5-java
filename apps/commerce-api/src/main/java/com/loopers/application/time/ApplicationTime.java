package com.loopers.application.time;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Component
public class ApplicationTime {

    private final Clock clock;

    // DB는 마이크로초까지 저장한다. 확정 이벤트처럼 저장 뒤 다시 쓰는 시각이 DB 값과 같도록 미리 같은 정밀도로 자른다.
    public ZonedDateTime now() {
        return ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
