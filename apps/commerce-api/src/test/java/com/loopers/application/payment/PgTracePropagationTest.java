package com.loopers.application.payment;

import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.pg.FakePgServer.RecordedRequest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 호출이 현재 trace 문맥을 W3C traceparent 헤더로 넘기는지 본다. Spring Boot 테스트는 기본으로 추적을 끄므로 다시 켠다.
 */
@AutoConfigureObservability
class PgTracePropagationTest extends PaymentFlowTestSupport {

    @Autowired
    private Tracer tracer;

    @DisplayName("현재 span 안에서 결제를 시작하면, PG create 요청의 traceparent 가 그 span 의 traceId 를 담는다.")
    @Test
    void pgCreateRequestCarriesCurrentTraceparent() {
        // arrange
        User user = saveUser(0);
        Product product = saveProduct("상품", 1_000, 5);
        Long orderId = placeOrder(user, product, 1);
        Span span = tracer.nextSpan().name("test-checkout").start();

        // act
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            orderFacade.confirm(command(user, orderId, "trace-attempt-1"));
        } finally {
            span.end();
        }

        // assert
        RecordedRequest create = pg.requests().stream()
            .filter(request -> request.method().equals("POST"))
            .findFirst()
            .orElseThrow();
        String traceparent = create.headers().get("traceparent");
        assertThat(traceparent).isNotNull();
        assertThat(traceparent.split("-")[1]).isEqualTo(span.context().traceId());
    }
}
