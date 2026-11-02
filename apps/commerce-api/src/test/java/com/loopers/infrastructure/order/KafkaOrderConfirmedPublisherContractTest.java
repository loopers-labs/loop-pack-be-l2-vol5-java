package com.loopers.infrastructure.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedUnpublished;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * api는 "이 JSON을 만든다"를 검증한다(docs/week6/decisions.md D-24). 계약 파일은 streamer도 같은 것을 읽는다 —
 * 어긋나면 둘 중 하나가 깨진다. 경로는 build.gradle.kts의 contract.dir 시스템 프로퍼티로 받는다.
 */
class KafkaOrderConfirmedPublisherContractTest {

    private static final String TOPIC = "order-events-contract";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode contract() throws IOException {
        String dir = System.getProperty("contract.dir");
        assertThat(dir).as("contract.dir 시스템 프로퍼티(build.gradle.kts tasks.test)").isNotBlank();
        return MAPPER.readTree(Files.readString(Path.of(dir, "order-confirmed.v1.json")));
    }

    private static OrderConfirmedPayload payload42(ZonedDateTime confirmedAt) {
        return new OrderConfirmedPayload(OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION,
            OrderConfirmedPayload.eventIdOf(42L), 42L, confirmedAt,
            List.of(new OrderConfirmedPayload.Item(7L, 2), new OrderConfirmedPayload.Item(8L, 1)));
    }

    @SuppressWarnings("unchecked")
    private static Sent publish(OrderConfirmedPayload payload) {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        new KafkaOrderConfirmedPublisher(template, TOPIC, new OrderConfirmedUnpublished(new SimpleMeterRegistry())).publish(payload);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq(TOPIC), key.capture(), value.capture());
        return new Sent(key.getValue(), value.getValue());
    }

    private record Sent(String key, String value) {}

    @DisplayName("계약 예시(주문 42)를 보내면, key는 \"42\"이고 value는 계약 JSON과 같으며 confirmedAt은 글자 그대로 같다.")
    @Test
    void producesContractJson() throws IOException {
        // arrange
        JsonNode contract = contract();
        ZonedDateTime confirmedAt = ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC);

        // act
        Sent sent = publish(payload42(confirmedAt));

        // assert
        JsonNode actual = MAPPER.readTree(sent.value());
        assertThat(sent.key()).isEqualTo("42");
        assertThat(actual).isEqualTo(contract);
        assertThat(actual.get("confirmedAt").asText()).isEqualTo(contract.get("confirmedAt").asText());
        assertThat(actual.get("orderId").asText()).isEqualTo(sent.key());
    }

    @DisplayName("confirmedAt 끝자리가 0이어도 소수 6자리를 그대로 쓴다. (.120000)")
    @Test
    void keepsTrailingZeros() throws IOException {
        // act
        Sent sent = publish(payload42(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 120_000_000, ZoneOffset.UTC)));

        // assert
        assertThat(MAPPER.readTree(sent.value()).get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00.120000Z");
    }

    @DisplayName("confirmedAt 소수가 0이어도 .000000을 쓴다.")
    @Test
    void keepsZeroFraction() throws IOException {
        // act
        Sent sent = publish(payload42(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, ZoneOffset.UTC)));

        // assert
        assertThat(MAPPER.readTree(sent.value()).get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00.000000Z");
    }

    @DisplayName("다른 시간대로 들어온 같은 순간도 UTC(Z)로 쓴다.")
    @Test
    void writesInUtc() throws IOException {
        // arrange
        ZonedDateTime seoul = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 123_456_000, ZoneId.of("Asia/Seoul"));

        // act
        Sent sent = publish(payload42(seoul));

        // assert
        assertThat(MAPPER.readTree(sent.value()).get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00.123456Z");
    }

    @DisplayName("전송 결과가 실패로 돌아오면 기다리지 않고 콜백에서 미발행(send_failed)으로 센다. 로그에는 orderId·eventId·topic이 남는다.")
    @Test
    @SuppressWarnings("unchecked")
    void countsSendFailure() {
        // arrange
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(OrderConfirmedUnpublished.class);
        logs.start();
        logger.addAppender(logs);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(template.send(anyString(), anyString(), anyString())).thenReturn(future);
        KafkaOrderConfirmedPublisher publisher =
            new KafkaOrderConfirmedPublisher(template, TOPIC, new OrderConfirmedUnpublished(meterRegistry));

        // act — publish는 결과를 기다리지 않고 돌아온다. 그 뒤에 실패가 도착한다.
        publisher.publish(payload42(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 0, ZoneOffset.UTC)));
        double beforeFailure = sendFailed(meterRegistry);
        future.completeExceptionally(new IllegalStateException("주입된 broker 실패"));
        logger.detachAppender(logs);

        // assert
        assertThat(beforeFailure).isZero();
        assertThat(sendFailed(meterRegistry)).isEqualTo(1.0);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                .contains("orderId=42", "eventId=order-confirmed:v1:42", "topic=" + TOPIC);
        });
    }

    private static double sendFailed(SimpleMeterRegistry meterRegistry) {
        return meterRegistry.get(OrderConfirmedUnpublished.METRIC)
            .tag(OrderConfirmedUnpublished.REASON_TAG, OrderConfirmedUnpublished.Reason.SEND_FAILED.tagValue())
            .counter().count();
    }
}
