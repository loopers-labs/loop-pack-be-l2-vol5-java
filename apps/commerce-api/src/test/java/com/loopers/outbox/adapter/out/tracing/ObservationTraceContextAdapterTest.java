package com.loopers.outbox.adapter.out.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W10 D-12 (나) trace 문맥 꺼내기·잇기. tracing 대신 carrier를 읽고 쓰는 handler를 달아, 어댑터가 traceparent를 carrier로 주고받는지만 본다.
 * 실제 tracing(OpenTelemetry)으로 [reconcile → relay → streamer]가 한 trace가 되는지는 run.sh가 Tempo로 확인한다.
 */
class ObservationTraceContextAdapterTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @DisplayName("trace 안에서 부르면 tracing이 carrier에 적어 준 traceparent를 돌려준다.")
    @Test
    void returnsTraceParentWrittenByTracing() {
        // given
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new WritesTraceParent());
        ObservationTraceContextAdapter adapter = new ObservationTraceContextAdapter(registry);

        // when
        String traceParent = adapter.currentTraceParent();

        // then
        assertThat(traceParent).isEqualTo(TRACE_PARENT);
    }

    @DisplayName("tracing이 없으면 traceparent는 null이다.")
    @Test
    void returnsNullWithoutTracing() {
        // given
        ObservationTraceContextAdapter adapter = new ObservationTraceContextAdapter(ObservationRegistry.create());

        // when
        String traceParent = adapter.currentTraceParent();

        // then
        assertThat(traceParent).isNull();
    }

    @DisplayName("저장한 traceparent를 받는 쪽 관측의 carrier로 넘기고, 그 안에서 실행한 결과를 돌려준다.")
    @Test
    void continuesFromStoredTraceParent() {
        // given
        ObservationRegistry registry = ObservationRegistry.create();
        ReadsTraceParent reader = new ReadsTraceParent();
        registry.observationConfig().observationHandler(reader);
        ObservationTraceContextAdapter adapter = new ObservationTraceContextAdapter(registry);

        // when
        String result = adapter.continueTrace(TRACE_PARENT, () -> "sent");

        // then
        assertThat(result).isEqualTo("sent");
        assertThat(reader.read).containsExactly(TRACE_PARENT);
    }

    @DisplayName("저장한 traceparent가 없으면 관측을 열지 않고 그냥 실행한다.")
    @Test
    void runsDirectlyWithoutTraceParent() {
        // given
        ObservationRegistry registry = ObservationRegistry.create();
        ReadsTraceParent reader = new ReadsTraceParent();
        registry.observationConfig().observationHandler(reader);
        ObservationTraceContextAdapter adapter = new ObservationTraceContextAdapter(registry);

        // when
        String result = adapter.continueTrace(null, () -> "sent");

        // then
        assertThat(result).isEqualTo("sent");
        assertThat(reader.read).isEmpty();
    }

    /** tracing의 보내는 쪽 handler처럼 지금 문맥을 carrier에 적는다 */
    private static final class WritesTraceParent implements ObservationHandler<SenderContext<Object>> {

        @Override
        public void onStart(SenderContext<Object> context) {
            context.getSetter().set(context.getCarrier(), ObservationTraceContextAdapter.TRACE_PARENT, TRACE_PARENT);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof SenderContext;
        }
    }

    /** tracing의 받는 쪽 handler처럼 carrier에서 부모 문맥을 읽는다 */
    private static final class ReadsTraceParent implements ObservationHandler<ReceiverContext<Object>> {

        private final List<String> read = new ArrayList<>();

        @Override
        public void onStart(ReceiverContext<Object> context) {
            read.add(context.getGetter().get(context.getCarrier(), ObservationTraceContextAdapter.TRACE_PARENT));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ReceiverContext;
        }
    }
}
