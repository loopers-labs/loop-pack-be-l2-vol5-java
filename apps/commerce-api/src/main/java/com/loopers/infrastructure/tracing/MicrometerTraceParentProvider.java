package com.loopers.infrastructure.tracing;

import com.loopers.application.outbox.TraceParentProvider;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 현재 span 문맥을 설정된 propagator(W3C)로 직렬화해 traceparent 값을 꺼낸다. */
@RequiredArgsConstructor
@Component
public class MicrometerTraceParentProvider implements TraceParentProvider {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Optional<String> current() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return Optional.ofNullable(carrier.get(TRACEPARENT));
    }
}
