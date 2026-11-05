package com.loopers.infrastructure.order;

import com.loopers.infrastructure.order.support.W6KafkaOrderEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O1 중 encoder 의 v1 wire 필드·타입·값과 실패 전파(설계 W7.2-6). 앱 공통 ObjectMapper 로의 직렬화는 저장 통합 테스트에서
 * 실제 bean 으로 다시 확인한다. Kafka value 의 이중 인코딩 여부(O5)는 이 테스트의 근거가 아니다.
 */
@DisplayName("JacksonOrderConfirmedMessageEncoder 는 확정 사건을 기존 v1 JSON 객체 문자열로 한 번 직렬화한다.")
class JacksonOrderConfirmedMessageEncoderTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed(42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC),
        List.of(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 1L)));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonOrderConfirmedMessageEncoder encoder = new JacksonOrderConfirmedMessageEncoder(objectMapper);

    @DisplayName("메타데이터는 사건에서 유도하고, payload 는 기존 v1 의 정확한 필드·숫자 타입·Z 표현·품목 순서를 가진 JSON 객체다.")
    @Test
    void encodesExactV1Fields() throws Exception {
        OrderOutboxMessage message = encoder.encode(EVENT);
        JsonNode json = objectMapper.readTree(message.payload());

        assertAll(
            () -> assertThat(message.eventId()).isEqualTo("order-confirmed:42"),
            () -> assertThat(message.orderId()).isEqualTo(42L),
            () -> assertThat(message.eventType()).isEqualTo("OrderConfirmed"),
            () -> assertThat(message.schemaVersion()).isEqualTo(1),
            () -> assertThat(message.messageKey()).isEqualTo("42"),
            () -> assertThat(json.isObject()).as("JSON 문자열이 아닌 객체").isTrue(),
            () -> assertThat(json.fieldNames()).toIterable()
                .containsExactly("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items"),
            () -> assertThat(json.path("eventType").isTextual()).isTrue(),
            () -> assertThat(json.path("eventType").asText()).isEqualTo("OrderConfirmed"),
            () -> assertThat(json.path("schemaVersion").isInt()).isTrue(),
            () -> assertThat(json.path("schemaVersion").asInt()).isEqualTo(1),
            () -> assertThat(json.path("eventId").asText()).isEqualTo("order-confirmed:42"),
            () -> assertThat(json.path("orderId").isIntegralNumber()).isTrue(),
            () -> assertThat(json.path("orderId").asLong()).isEqualTo(42L),
            () -> assertThat(json.path("confirmedAt").isTextual()).isTrue(),
            () -> assertThat(json.path("confirmedAt").asText()).isEqualTo("2026-10-28T07:00:00.123456Z"),
            () -> assertThat(json.path("items").toString())
                .isEqualTo("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"),
            () -> assertThat(message.payload()).isEqualTo("{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,"
                + "\"eventId\":\"order-confirmed:42\",\"orderId\":42,\"confirmedAt\":\"2026-10-28T07:00:00.123456Z\","
                + "\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]}")
        );
    }

    /** valueToTree 는 long 필드를 LongNode 로, 파싱은 작은 정수를 IntNode 로 만들어 트리 비교가 숫자 노드 종류에서 갈린다. 문자열로 비교한다. */
    @DisplayName("같은 ObjectMapper 로 W6 발행 adapter 의 v1 DTO 를 직렬화한 문자열과 같다.")
    @Test
    void matchesExistingAdapterWire() throws Exception {
        String existing = objectMapper.writeValueAsString(W6KafkaOrderEventPublisher.OrderConfirmedV1.from(EVENT));

        assertThat(encoder.encode(EVENT).payload()).isEqualTo(existing);
    }

    @DisplayName("confirmedAt 은 ISO_INSTANT 이므로 0 인 소수초는 생략하고 밀리초 값은 3자리로 쓴다. 최대 long 값도 정수로 쓴다.")
    @Test
    void keepsInstantRepresentationAndLongRange() throws Exception {
        OrderConfirmed wholeSecond = new OrderConfirmed(Long.MAX_VALUE,
            ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(Long.MAX_VALUE, Long.MAX_VALUE)));
        OrderConfirmed millis = new OrderConfirmed(1L,
            ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 120_000_000, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(1L, 1L)));

        OrderOutboxMessage message = encoder.encode(wholeSecond);
        JsonNode json = objectMapper.readTree(message.payload());
        assertAll(
            () -> assertThat(json.path("confirmedAt").asText()).isEqualTo("2026-10-28T07:00:00Z"),
            () -> assertThat(message.messageKey()).isEqualTo("9223372036854775807"),
            () -> assertThat(json.path("orderId").isLong()).isTrue(),
            () -> assertThat(json.path("orderId").asLong()).isEqualTo(Long.MAX_VALUE),
            () -> assertThat(json.path("items").get(0).path("quantity").asLong()).isEqualTo(Long.MAX_VALUE),
            () -> assertThat(objectMapper.readTree(encoder.encode(millis).payload()).path("confirmedAt").asText())
                .isEqualTo("2026-10-28T07:00:00.120Z")
        );
    }

    @DisplayName("사건이 없으면 직렬화 전에 INTERNAL_ERROR 다.")
    @Test
    void rejectsMissingEvent() {
        assertThatThrownBy(() -> encoder.encode(null))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }

    @DisplayName("직렬화 예외는 원문 메시지·cause 없이 예외 클래스명만 담은 INTERNAL_ERROR 로 전파한다.")
    @Test
    void propagatesSerializationFailureSafely() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any()))
            .thenThrow(new JsonProcessingException("민감한-원문 {\"orderId\":42}") { })
            .thenThrow(new IllegalStateException("민감한-원문", new IllegalArgumentException("원인-원문")));
        JacksonOrderConfirmedMessageEncoder failingEncoder = new JacksonOrderConfirmedMessageEncoder(failing);

        assertAll(
            () -> assertThatThrownBy(() -> failingEncoder.encode(EVENT))
                .isInstanceOf(CoreException.class)
                .hasNoCause()
                .hasMessageNotContaining("민감한-원문").hasMessageNotContaining("orderId")
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> failingEncoder.encode(EVENT))
                .isInstanceOf(CoreException.class)
                .hasNoCause()
                .hasMessageContaining("java.lang.IllegalStateException")
                .hasMessageNotContaining("민감한-원문").hasMessageNotContaining("원인-원문")
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
        );
        verify(failing, times(2)).writeValueAsString(any());
    }
}
