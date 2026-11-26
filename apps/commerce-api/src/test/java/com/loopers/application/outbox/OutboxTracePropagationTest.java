package com.loopers.application.outbox;

import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.kafka.TopicReader;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정 요청의 trace 가 Outbox 를 거쳐 다른 스레드의 발행까지 이어지는지 본다. Spring Boot 테스트는 기본으로 추적을 끄므로 다시 켠다.
 */
@AutoConfigureObservability
class OutboxTracePropagationTest extends PaymentFlowTestSupport {

    private static final String TOPIC = "outbox-trace-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private Tracer tracer;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @DisplayName("확정 요청의 span 안에서 PG 성공을 반영하면, relay 가 나중에 보낸 레코드의 traceparent 가 그 span 의 traceId 를 담는다.")
    @Test
    void kafkaRecordContinuesTraceOfConfirmingRequest() {
        // arrange
        User user = saveUser(0);
        Product product = saveProduct("상품", 1_000, 5);
        Long orderId = placeOrder(user, product, 1);
        orderFacade.confirm(command(user, orderId, "trace-attempt-1"));
        pg.setStatus(pgKeyOf(orderId), "SUCCESS");
        Span reconcile = tracer.nextSpan().name("test-reconcile").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(reconcile)) {
            paymentResultLookupFacade.lookupMyPaymentResult(user.getId(), orderId);
        } finally {
            reconcile.end();
        }

        try (TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {
            // act: relay 는 span 밖(스케줄러 스레드와 같은 조건)에서 실행한다.
            outboxRelay.relayOnce();

            // assert
            ConsumerRecord<String, byte[]> record = reader.awaitRecords(1, Duration.ofSeconds(10)).get(0);
            assertThat(traceIdOf(record)).isEqualTo(reconcile.context().traceId());
        }
    }

    @DisplayName("trace 문맥 없이 기록된 Outbox 도 발행되고, 레코드에는 새 trace 가 실린다.")
    @Test
    void outboxWithoutTraceParent_isPublishedUnderNewTrace() {
        // arrange
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[]{0x01},
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        try (TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {
            // act
            outboxPublisher.publish(outbox);

            // assert
            ConsumerRecord<String, byte[]> record = reader.awaitRecords(1, Duration.ofSeconds(10)).get(0);
            assertThat(traceIdOf(record)).hasSize(32);
        }
    }

    private static String traceIdOf(ConsumerRecord<String, byte[]> record) {
        Header header = record.headers().lastHeader("traceparent");
        assertThat(header).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8).split("-")[1];
    }
}
