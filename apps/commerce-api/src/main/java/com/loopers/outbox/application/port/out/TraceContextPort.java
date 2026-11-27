package com.loopers.outbox.application.port.out;

import java.util.function.Supplier;

/**
 * 결과를 반영한 요청의 trace 문맥을 Outbox 행에 실어 relay까지 잇는다 (W10 plan.md D-12 (나)).
 * relay는 스케줄러가 따로 돌아 새 trace로 시작한다 — 저장해 둔 문맥을 부모로 삼으면 [결과 반영 → Outbox → Kafka → 소비]가 한 trace가 된다.
 */
public interface TraceContextPort {

    /**
     * 지금 실행 중인 trace의 문맥 (W3C traceparent). trace가 없으면 null.
     */
    String currentTraceParent();

    /**
     * 저장해 둔 문맥을 부모로 삼아 action을 실행한다. traceParent가 null이면 그냥 실행한다.
     */
    <T> T continueTrace(String traceParent, Supplier<T> action);
}
