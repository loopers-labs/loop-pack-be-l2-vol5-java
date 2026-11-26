package com.loopers.application.outbox;

import java.util.Optional;

/** 현재 실행 중인 trace 문맥을 W3C traceparent 문자열로 돌려준다. 문맥이 없으면 비어 있다. */
public interface TraceParentProvider {

    Optional<String> current();
}
