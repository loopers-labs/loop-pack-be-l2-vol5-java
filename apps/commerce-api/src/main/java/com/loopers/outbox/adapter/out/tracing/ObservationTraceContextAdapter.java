package com.loopers.outbox.adapter.out.tracing;

import com.loopers.outbox.application.port.out.TraceContextPort;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Micrometer Observation으로 trace 문맥을 꺼내고 잇는다 (W10 plan.md D-12 (나)). tracing 라이브러리를 직접 부르지 않는다.
 * <ul>
 *   <li>꺼내기: 보내는 쪽 관측(SenderContext)을 하나 열면 tracing이 지금 문맥을 carrier에 traceparent로 적어 준다 — HTTP·Kafka 발행과 같은 방식.</li>
 *   <li>잇기: 받는 쪽 관측(ReceiverContext)에 저장한 traceparent를 주면 그 문맥의 자식으로 시작한다 — 그 안의 Kafka 발행이 같은 trace가 된다.</li>
 * </ul>
 * tracing이 꺼져 있으면(테스트·전송처 없음) carrier가 비어 null을 돌려주고, 잇기는 그냥 실행한다.
 */
@RequiredArgsConstructor
@Component
public class ObservationTraceContextAdapter implements TraceContextPort {

    static final String TRACE_PARENT = "traceparent";

    private final ObservationRegistry observationRegistry;

    @Override
    public String currentTraceParent() {
        Map<String, String> carrier = new HashMap<>();
        SenderContext<Map<String, String>> context = new SenderContext<>((target, key, value) -> target.put(key, value));
        context.setCarrier(carrier);
        Observation.createNotStarted("order.outbox.append", () -> context, observationRegistry).observe(() -> { });
        return carrier.get(TRACE_PARENT);
    }

    @Override
    public <T> T continueTrace(String traceParent, Supplier<T> action) {
        if (traceParent == null) {
            return action.get();
        }
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>((source, key) -> source.get(key));
        context.setCarrier(Map.of(TRACE_PARENT, traceParent));
        return Observation.createNotStarted("order.outbox.relay", () -> context, observationRegistry).observe(action);
    }
}
