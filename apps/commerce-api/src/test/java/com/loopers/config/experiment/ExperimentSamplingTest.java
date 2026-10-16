package com.loopers.config.experiment;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentSamplingTest {
    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.009, 0.01, 0.99})
    void selectsAtRequestStartAndCountsUnsampledRequests(double draw) throws Exception {
        AtomicInteger active = new AtomicInteger();
        var valve = new ExperimentConfiguration.TraceValve(active, 0.01, () -> draw);
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve next = mock(Valve.class);
        when(request.getHeader("X-Run-ID")).thenReturn("run");
        when(request.getHeader("X-Request-ID")).thenReturn("request");
        when(request.getRequestURI()).thenReturn("/api/v1/products");
        valve.setNext(next);
        doAnswer(invocation -> {
            assertThat(active.get()).isEqualTo(1);
            assertThat(ExperimentTrace.current() != null).isEqualTo(draw < 0.01);
            throw new jakarta.servlet.ServletException("downstream");
        }).when(next).invoke(request, response);
        assertThatThrownBy(() -> valve.invoke(request, response))
            .isInstanceOf(jakarta.servlet.ServletException.class).hasMessage("downstream");
        assertThat(active.get()).isZero();
        assertThat(ExperimentTrace.current()).isNull();
        verify(next).invoke(request, response);
        verify(request, times(draw < 0.01 ? 1 : 0)).setAttribute(eq("experiment.trace"), any());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidSamplingRates(double rate) {
        assertThatThrownBy(() -> new ExperimentConfiguration.TraceValve(new AtomicInteger(), rate, () -> 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
