package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxMessageException;
import com.loopers.domain.order.OrderOutboxMessageException.Code;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * O1 남은 부분(설계 W7.3-6·W7.4-3): 저장 원문 verifier 의 엄격 v1 검증·정규화·같은 사실 비교를 순수 입력으로 확인한다.
 * DB·Kafka 를 쓰지 않으므로 실제 저장 행·wire 의 근거가 아니다. 기준 원문은 실제 encoder 출력이다.
 */
@DisplayName("Outbox verifier 는 저장 원문을 엄격한 v1 으로만 받고, 표현 차이는 같은 사실로·실제 값 차이는 충돌로 구분한다.")
class JacksonOrderOutboxMessageVerifierTest {

    private static final ZonedDateTime AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_450_000, ZoneOffset.UTC);
    private static final OrderConfirmed EVENT = new OrderConfirmed(42L, AT,
        List.of(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 1L)));
    private static final String HEAD = "\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":42,";
    private static final String TIME = "\"confirmedAt\":\"2026-10-28T07:00:00.12345Z\",";
    private static final String ITEMS = "\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]";

    private final JacksonOrderOutboxMessageVerifier verifier = new JacksonOrderOutboxMessageVerifier();
    private final OrderOutboxMessage encoded = new JacksonOrderConfirmedMessageEncoder(new ObjectMapper()).encode(EVENT);

    private static OrderOutboxStoredMessage stored(String payload) {
        return new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", payload);
    }

    private OrderOutboxStoredMessage storedEncoded() {
        return new OrderOutboxStoredMessage(encoded.eventId(), encoded.orderId(), encoded.eventType(), encoded.schemaVersion(),
            encoded.messageKey(), encoded.payload());
    }

    @DisplayName("실제 encoder 출력을 받아 같은 확정 사건으로 되돌리고, 같은 사건과의 비교를 통과한다.")
    @Test
    void acceptsEncoderOutput() {
        assertAll(
            () -> assertThat(verifier.parseAndValidate(storedEncoded())).isEqualTo(EVENT),
            () -> assertThatCode(() -> verifier.requireSameFact(EVENT, storedEncoded())).doesNotThrowAnyException()
        );
    }

    /** 속성·품목 순서, 같은 상품의 분할 원본, 같은 순간의 소수초 후행 0, 미사용 속성은 같은 사실이다. */
    static Stream<String> equivalentPayloads() {
        return Stream.of(
            "{" + ITEMS + "," + TIME + HEAD.substring(0, HEAD.length() - 1) + "}",
            "{" + HEAD + TIME + "\"items\":[{\"quantity\":1,\"productId\":8},{\"productId\":7,\"quantity\":2}]}",
            "{" + HEAD + TIME + "\"items\":[{\"productId\":7,\"quantity\":1},{\"productId\":8,\"quantity\":1},{\"productId\":7,\"quantity\":1}]}",
            "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.123450Z\"," + ITEMS + "}",
            "{" + HEAD + TIME + ITEMS + ",\"note\":{\"any\":[1,\"x\"]},\"price\":null}",
            " \n{" + HEAD + TIME + ITEMS + "}\n ");
    }

    @DisplayName("표현만 다른 원문은 정규화 결과와 같은 사실 비교가 encoder 기준과 같다.")
    @ParameterizedTest
    @MethodSource("equivalentPayloads")
    void equivalentRepresentationsAreSameFact(String payload) {
        assertAll(
            () -> assertThat(verifier.parseAndValidate(stored(payload))).isEqualTo(EVENT),
            () -> assertThatCode(() -> verifier.requireSameFact(EVENT, stored(payload))).doesNotThrowAnyException()
        );
    }

    @DisplayName("기대 사건과 실제 순간·상품·수량·주문이 다르면 원문이 유효해도 사실 충돌이다.")
    @Test
    void differentFactIsMismatch() {
        List<OrderConfirmed> others = List.of(
            new OrderConfirmed(42L, AT.plusNanos(1_000), EVENT.items()),
            new OrderConfirmed(42L, AT, List.of(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(9L, 1L))),
            new OrderConfirmed(42L, AT, List.of(new OrderConfirmed.Item(7L, 3L), new OrderConfirmed.Item(8L, 1L))),
            new OrderConfirmed(42L, AT, List.of(new OrderConfirmed.Item(7L, 2L))),
            new OrderConfirmed(43L, AT, EVENT.items()));
        assertThat(others).allSatisfy(other -> assertFailure(() -> verifier.requireSameFact(other, storedEncoded()),
            Code.OUTBOX_FACT_MISMATCH, encoded.payload()));
    }

    @DisplayName("저장 원문이 유효하지 않으면 사실 비교도 그 원문의 안전 code 로 실패한다.")
    @Test
    void sameFactValidatesStoredFirst() {
        assertFailure(() -> verifier.requireSameFact(EVENT, stored("{" + HEAD + TIME + "\"items\":[]}")),
            Code.INVALID_OUTBOX_MESSAGE, "items");
    }

    static Stream<Arguments> invalidPayloads() {
        String body = HEAD + TIME;
        return Stream.of(
            arguments("빈 본문", " "),
            arguments("JSON 아님", "order-confirmed"),
            arguments("배열 최상위", "[{" + body + ITEMS + "}]"),
            arguments("이중 인코딩된 JSON 문자열", "\"{\\\"eventType\\\":\\\"OrderConfirmed\\\"}\""),
            arguments("중복 key", "{" + body + ITEMS + ",\"orderId\":42}"),
            arguments("뒤따르는 token", "{" + body + ITEMS + "} {}"),
            arguments("주석", "{" + body + ITEMS + "/* x */}"),
            arguments("eventType 누락", "{\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":42," + TIME + ITEMS + "}"),
            arguments("schemaVersion 문자열", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":\"1\",\"eventId\":\"order-confirmed:42\",\"orderId\":42," + TIME + ITEMS + "}"),
            arguments("schemaVersion 소수", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1.0,\"eventId\":\"order-confirmed:42\",\"orderId\":42," + TIME + ITEMS + "}"),
            arguments("eventId null", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":null,\"orderId\":42," + TIME + ITEMS + "}"),
            arguments("orderId 문자열", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":\"42\"," + TIME + ITEMS + "}"),
            arguments("orderId 지수", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":4.2e1," + TIME + ITEMS + "}"),
            arguments("orderId 선행 0", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":042," + TIME + ITEMS + "}"),
            arguments("본문 orderId 불일치", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:42\",\"orderId\":43," + TIME + ITEMS + "}"),
            arguments("본문 eventId 불일치", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:042\",\"orderId\":42," + TIME + ITEMS + "}"),
            arguments("confirmedAt 누락", "{" + HEAD + ITEMS + "}"),
            arguments("confirmedAt 숫자", "{" + HEAD + "\"confirmedAt\":1761634800," + ITEMS + "}"),
            arguments("초 없음", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00Z\"," + ITEMS + "}"),
            arguments("소수초 7자리", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.1234567Z\"," + ITEMS + "}"),
            arguments("UTC offset 표기", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.12345+00:00\"," + ITEMS + "}"),
            arguments("zone 없음", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.12345\"," + ITEMS + "}"),
            arguments("소문자 z", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.12345z\"," + ITEMS + "}"),
            arguments("없는 날짜", "{" + HEAD + "\"confirmedAt\":\"2026-02-30T07:00:00Z\"," + ITEMS + "}"),
            arguments("24시", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T24:00:00Z\"," + ITEMS + "}"),
            arguments("DATETIME 하한 밖 연도", "{" + HEAD + "\"confirmedAt\":\"0999-12-31T23:59:59Z\"," + ITEMS + "}"),
            arguments("5자리 연도", "{" + HEAD + "\"confirmedAt\":\"10000-01-01T00:00:00Z\"," + ITEMS + "}"),
            arguments("items 누락", "{" + HEAD + "\"confirmedAt\":\"2026-10-28T07:00:00.12345Z\"}"),
            arguments("빈 items", "{" + body + "\"items\":[]}"),
            arguments("items 객체", "{" + body + "\"items\":{\"productId\":7,\"quantity\":2}}"),
            arguments("품목 원소 숫자", "{" + body + "\"items\":[7]}"),
            arguments("quantity 0", "{" + body + "\"items\":[{\"productId\":7,\"quantity\":0}]}"),
            arguments("원본 음수는 합이 양수여도 거절", "{" + body + "\"items\":[{\"productId\":7,\"quantity\":-1},{\"productId\":7,\"quantity\":3}]}"),
            arguments("productId 소수", "{" + body + "\"items\":[{\"productId\":7.0,\"quantity\":1}]}"),
            arguments("quantity long 범위 밖", "{" + body + "\"items\":[{\"productId\":7,\"quantity\":9223372036854775808}]}"),
            arguments("상품별 합산 overflow", "{" + body + "\"items\":[{\"productId\":7,\"quantity\":9223372036854775807},{\"productId\":7,\"quantity\":1}]}"),
            arguments("잘린 JSON", "{" + body + ITEMS),
            arguments("UTF-8 로 표현할 수 없는 고립 surrogate", "{" + body + ITEMS + ",\"note\":\"\uD800\"}"));
    }

    @DisplayName("필수·타입·숫자·품목·시각·본문 identity 위반은 원문을 노출하지 않는 형식 오류다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void rejectsInvalidPayload(String name, String payload) {
        assertFailure(() -> verifier.parseAndValidate(stored(payload)), Code.INVALID_OUTBOX_MESSAGE, payload);
    }

    @DisplayName("본문의 eventType·schemaVersion 이 v1 이 아니면 다른 필드를 v1 규칙으로 판정하지 않고 지원하지 않는 형식이다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"eventType\":\"OrderCancelled\",\"schemaVersion\":1}",
        "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":2,\"items\":\"v2\"}"
    })
    void rejectsUnsupportedBody(String payload) {
        assertFailure(() -> verifier.parseAndValidate(stored(payload)), Code.UNSUPPORTED_SCHEMA, payload);
    }

    static Stream<Arguments> invalidMetadata() {
        String payload = "{" + HEAD + TIME + ITEMS + "}";
        return Stream.of(
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderCancelled", 1, "42", payload), Code.UNSUPPORTED_SCHEMA),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 2, "42", payload), Code.UNSUPPORTED_SCHEMA),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, null, 1, "42", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", null, "42", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", null, "OrderConfirmed", 1, "42", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 0L, "OrderConfirmed", 1, "0", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:43", 42L, "OrderConfirmed", 1, "42", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 43L, "OrderConfirmed", 1, "43", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "042", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, " 42", payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, null, payload), Code.INVALID_OUTBOX_MESSAGE),
            arguments(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", null), Code.INVALID_OUTBOX_MESSAGE));
    }

    @DisplayName("저장 메타데이터의 형식·identity·key·payload 위반은 본문보다 먼저 안전 code 로 거절한다.")
    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsInvalidMetadata(OrderOutboxStoredMessage message, Code code) {
        assertFailure(() -> verifier.parseAndValidate(message), code, "order-confirmed:4");
    }

    @DisplayName("입력 없음 같은 호출자 오류는 형식 오류로 바꾸지 않고 그대로 드러낸다.")
    @Test
    void programErrorsAreNotFormatErrors() {
        assertAll(
            () -> assertThatThrownBy(() -> verifier.parseAndValidate(null)).isInstanceOf(NullPointerException.class),
            () -> assertThatThrownBy(() -> verifier.requireSameFact(null, storedEncoded())).isInstanceOf(NullPointerException.class)
        );
    }

    /** 안전 code 와 함께, 메시지가 code 이름뿐이고 cause 가 없어 원문·parser 메시지가 실리지 않음을 확인한다. */
    private static void assertFailure(Runnable call, Code code, String rawText) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderOutboxMessageException.class, failure -> assertAll(
            () -> assertThat(failure.code()).isEqualTo(code),
            () -> assertThat(failure.getMessage()).isEqualTo(code.name()).doesNotContain(rawText),
            () -> assertThat(failure.getCause()).isNull()
        ));
    }
}
