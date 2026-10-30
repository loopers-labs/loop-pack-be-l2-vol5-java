package com.loopers.interfaces.consumer.order;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;

/** C1: OrderConfirmed v1 wire 의 엄격한 파싱·검증·정규화(소비 설계 4절). I/O 없는 순수 검증이다. */
@DisplayName("OrderConfirmed v1 parser 는 계약 밖 입력을 오류로 거절하고 같은 사실의 표현 차이는 같은 값으로 만든다.")
class OrderConfirmedParserTest {

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:42","orderId":42,
         "confirmedAt":"2026-10-28T07:00:00.123456Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """;

    private final OrderConfirmedParser parser = new OrderConfirmedParser();

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private PurchaseActivityException reject(String key, byte[] value) {
        PurchaseActivityException error = catchThrowableOfType(PurchaseActivityException.class,
            () -> parser.parseAndValidate(key, value));
        assertThat(error).as("거절되어야 한다").isNotNull();
        return error;
    }

    private void assertInvalid(String key, String json) {
        assertThat(reject(key, utf8(json)).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT);
    }

    private static String replace(String target, String replacement) {
        String replaced = VALID.replace(target, replacement);
        assertThat(replaced).as("치환 대상이 있어야 한다: " + target).isNotEqualTo(VALID);
        return replaced;
    }

    @DisplayName("정상 입력은 고정 필드·UTC 마이크로초 시각·상품 ID 오름차순 품목의 불변 값이 된다.")
    @Test
    void parsesValidEvent() {
        OrderConfirmation event = parser.parseAndValidate("42", utf8(VALID));

        assertAll(
            () -> assertThat(event.eventType()).isEqualTo("OrderConfirmed"),
            () -> assertThat(event.schemaVersion()).isEqualTo(1),
            () -> assertThat(event.eventId()).isEqualTo("order-confirmed:42"),
            () -> assertThat(event.orderId()).isEqualTo(42L),
            () -> assertThat(event.confirmedAt()).isEqualTo(ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC)),
            () -> assertThat(event.items()).containsExactly(new OrderConfirmation.Item(7, 2), new OrderConfirmation.Item(8, 1)),
            () -> assertThat(event.items().getClass().getName()).startsWith("java.util.ImmutableCollections")
        );
    }

    @DisplayName("속성 순서·품목 순서·소수초 후행 0·같은 상품의 분할·미사용 추가 속성은 같은 사실이다.")
    @Test
    void representationDifferencesAreSameFact() {
        OrderConfirmation base = parser.parseAndValidate("42", utf8(VALID));
        String reordered = """
            {"items":[{"quantity":1,"productId":8},{"productId":7,"quantity":1,"note":"x"},{"productId":7,"quantity":1}],
             "confirmedAt":"2026-10-28T07:00:00.123456Z","orderId":42,"eventId":"order-confirmed:42","schemaVersion":1,
             "eventType":"OrderConfirmed","extra":{"nested":[1,2]}}
            """;
        OrderConfirmation tenths = parser.parseAndValidate("42", utf8(replace("07:00:00.123456Z", "07:00:00.1Z")));
        OrderConfirmation trailing = parser.parseAndValidate("42", utf8(replace("07:00:00.123456Z", "07:00:00.100000Z")));
        OrderConfirmation whole = parser.parseAndValidate("42", utf8(replace("07:00:00.123456Z", "07:00:00Z")));

        assertAll(
            () -> assertThat(parser.parseAndValidate("42", utf8(reordered))).isEqualTo(base),
            () -> assertThat(tenths).isEqualTo(trailing),
            () -> assertThat(tenths.confirmedAt().getNano()).isEqualTo(100_000_000),
            () -> assertThat(whole.confirmedAt().getNano()).isZero()
        );
    }

    @DisplayName("원본 항목의 -1 은 같은 상품의 3 과 합치기 전에 거절한다.")
    @Test
    void rejectsNegativeOriginalBeforeMerge() {
        assertInvalid("42", replace("[{\"productId\":7,\"quantity\":2}", "[{\"productId\":7,\"quantity\":-1},{\"productId\":7,\"quantity\":3}"));
    }

    @DisplayName("상품별 합계가 long 을 넘으면 거절한다.")
    @Test
    void rejectsQuantityOverflow() {
        assertInvalid("42", replace("{\"productId\":8,\"quantity\":1}",
            "{\"productId\":8,\"quantity\":9223372036854775807},{\"productId\":8,\"quantity\":1}"));
    }

    @Nested
    @DisplayName("형식 오류는 INVALID_EVENT 다.")
    class FormatErrors {

        @DisplayName("null·빈 value")
        @Test
        void emptyValue() {
            assertAll(
                () -> assertThat(reject("42", null).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", new byte[0]).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT)
            );
        }

        @DisplayName("잘못된 UTF-8 과 UTF-16/32 인코딩은 디코더나 JSON 단계에서 거절한다.")
        @Test
        void encodings() {
            byte[] malformed = utf8(VALID);
            malformed[10] = (byte) 0xC3; // 뒤 바이트가 연속 바이트가 아닌 잘린 2바이트 시퀀스
            assertAll(
                () -> assertThat(reject("42", malformed).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", VALID.getBytes(StandardCharsets.UTF_16)).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", VALID.getBytes(StandardCharsets.UTF_16LE)).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", VALID.getBytes(StandardCharsets.UTF_16BE)).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", VALID.getBytes(java.nio.charset.Charset.forName("UTF-32LE"))).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
                () -> assertThat(reject("42", VALID.getBytes(java.nio.charset.Charset.forName("UTF-32BE"))).getCode()).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT)
            );
        }

        @DisplayName("중복 property·뒤따르는 token·추가 JSON 문서·object 가 아닌 최상위")
        @Test
        void jsonStructure() {
            assertAll(
                () -> assertInvalid("42", replace("\"orderId\":42,", "\"orderId\":42,\"orderId\":42,")),
                () -> assertInvalid("42", replace("{\"productId\":7,\"quantity\":2}", "{\"productId\":7,\"quantity\":2,\"quantity\":2}")),
                () -> assertInvalid("42", VALID + " {}"),
                () -> assertInvalid("42", VALID + " 1"),
                () -> assertInvalid("42", VALID.trim() + "]"),
                () -> assertInvalid("42", "[" + VALID + "]"),
                () -> assertInvalid("42", "\"text\""),
                () -> assertInvalid("42", "{\"eventType\":")
            );
        }

        @DisplayName("필수 필드의 누락·null")
        @ParameterizedTest
        @ValueSource(strings = {"eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items"})
        void missingOrNull(String field) {
            String json = VALID.replaceFirst("\"" + field + "\":(\"[^\"]*\"|\\d+|\\[[^\\]]*\\])", "\"" + field + "\":null");
            String missing = VALID.replaceFirst("\"" + field + "\":(\"[^\"]*\"|\\d+|\\[[^\\]]*\\])", "\"unused\":0");
            assertThat(json).contains("\"" + field + "\":null");
            assertThat(missing).doesNotContain("\"" + field + "\"");
            assertAll(
                () -> assertInvalid("42", json),
                () -> assertInvalid("42", missing)
            );
        }

        @DisplayName("품목 원소의 누락·null")
        @Test
        void itemFieldMissingOrNull() {
            assertAll(
                () -> assertInvalid("42", replace("{\"productId\":7,\"quantity\":2}", "{\"productId\":7}")),
                () -> assertInvalid("42", replace("{\"productId\":7,\"quantity\":2}", "{\"quantity\":2}")),
                () -> assertInvalid("42", replace("{\"productId\":7,\"quantity\":2}", "{\"productId\":null,\"quantity\":2}")),
                () -> assertInvalid("42", replace("{\"productId\":7,\"quantity\":2}", "{\"productId\":7,\"quantity\":null}"))
            );
        }

        @DisplayName("자동 coercion 대상인 문자열 숫자·소수·지수·불리언·단일 object 품목")
        @Test
        void noCoercion() {
            assertAll(
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":\"42\"")),
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":42.0")),
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":4.2e1")),
                () -> assertInvalid("42", replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")),
                () -> assertInvalid("42", replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")),
                () -> assertInvalid("42", replace("\"schemaVersion\":1", "\"schemaVersion\":true")),
                () -> assertInvalid("42", replace("\"quantity\":2", "\"quantity\":\"2\"")),
                () -> assertInvalid("42", replace("\"eventType\":\"OrderConfirmed\"", "\"eventType\":[\"OrderConfirmed\"]")),
                () -> assertInvalid("42", replace("\"eventId\":\"order-confirmed:42\"", "\"eventId\":42")),
                () -> assertInvalid("42", replace("\"confirmedAt\":\"2026-10-28T07:00:00.123456Z\"", "\"confirmedAt\":1761634800")),
                () -> assertInvalid("42", replace("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]", "{\"productId\":7,\"quantity\":2}")),
                () -> assertInvalid("42", replace("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]", "[]")),
                () -> assertInvalid("42", replace("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]", "[7,8]")),
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":042"))
            );
        }

        @DisplayName("지원하지 않는 유형·버전")
        @Test
        void typeAndVersion() {
            assertAll(
                () -> assertInvalid("42", replace("\"OrderConfirmed\"", "\"OrderCancelled\"")),
                () -> assertInvalid("42", replace("\"OrderConfirmed\"", "\"orderconfirmed\"")),
                () -> assertInvalid("42", replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
                () -> assertInvalid("42", replace("\"schemaVersion\":1", "\"schemaVersion\":0"))
            );
        }

        @DisplayName("0·음수·long 범위 밖 ID·수량")
        @Test
        void ranges() {
            assertAll(
                () -> assertInvalid("0", replace("\"orderId\":42", "\"orderId\":0").replace("order-confirmed:42", "order-confirmed:0")),
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":-42")),
                () -> assertInvalid("42", replace("\"orderId\":42", "\"orderId\":9223372036854775808")),
                () -> assertInvalid("42", replace("\"productId\":7", "\"productId\":0")),
                () -> assertInvalid("42", replace("\"productId\":7", "\"productId\":-7")),
                () -> assertInvalid("42", replace("\"quantity\":2", "\"quantity\":0")),
                () -> assertInvalid("42", replace("\"quantity\":2", "\"quantity\":99999999999999999999"))
            );
        }

        @DisplayName("key 의 null·공백·선행 0·부호·비숫자")
        @Test
        void keyFormat() {
            assertAll(
                () -> assertInvalid(null, VALID),
                () -> assertInvalid("", VALID),
                () -> assertInvalid(" 42", VALID),
                () -> assertInvalid("42 ", VALID),
                () -> assertInvalid("042", VALID),
                () -> assertInvalid("+42", VALID),
                () -> assertInvalid("order-42", VALID)
            );
        }

        @DisplayName("eventId 의 공백·비 ASCII·64자 초과")
        @Test
        void eventIdFormat() {
            assertAll(
                () -> assertInvalid("42", replace("\"eventId\":\"order-confirmed:42\"", "\"eventId\":\"   \"")),
                () -> assertInvalid("42", replace("\"eventId\":\"order-confirmed:42\"", "\"eventId\":\"order-confirmed：42\"")),
                () -> assertInvalid("42", replace("\"eventId\":\"order-confirmed:42\"", "\"eventId\":\"" + "x".repeat(65) + "\""))
            );
        }

        @DisplayName("confirmedAt 의 Z 아닌 offset·7자리 이상 소수초·초 생략·범위·존재하지 않는 날짜")
        @ParameterizedTest
        @ValueSource(strings = {
            "2026-10-28T07:00:00.123456+00:00", "2026-10-28T16:00:00.123456+09:00", "2026-10-28T07:00:00.123456",
            "2026-10-28T07:00:00.1234567Z", "2026-10-28T07:00:00.123456789Z", "2026-10-28T07:00Z", "2026-10-28T07:00:00.Z",
            "2026-10-28 07:00:00Z", "2026-10-28t07:00:00z", "0999-12-31T23:59:59Z", "+10000-01-01T00:00:00Z",
            "2026-02-29T00:00:00Z", "2026-10-28T24:00:00Z", "2026-10-28T23:60:00Z", "2026-10-28T23:59:60Z",
            "2026-13-01T00:00:00Z", "２026-10-28T07:00:00Z", "1761634800", ""})
        void confirmedAtFormat(String value) {
            assertInvalid("42", replace("2026-10-28T07:00:00.123456Z", value));
        }

        @DisplayName("DATETIME(6) 범위의 양 끝은 받는다.")
        @Test
        void confirmedAtBounds() {
            assertAll(
                () -> assertThat(parser.parseAndValidate("42", utf8(replace("2026-10-28T07:00:00.123456Z", "1000-01-01T00:00:00Z")))
                    .confirmedAt().getYear()).isEqualTo(1000),
                () -> assertThat(parser.parseAndValidate("42", utf8(replace("2026-10-28T07:00:00.123456Z", "9999-12-31T23:59:59.999999Z")))
                    .confirmedAt().getNano()).isEqualTo(999_999_000)
            );
        }
    }

    @Nested
    @DisplayName("정상 스키마의 ID 관계 불일치는 IDENTITY_CONFLICT 다.")
    class IdentityConflicts {

        @DisplayName("key 와 orderId 가 다르다.")
        @Test
        void keyDiffers() {
            assertThat(reject("43", utf8(VALID)).getCode()).isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT);
        }

        @DisplayName("eventId 가 orderId 에서 유도한 값과 다르다(같은 ID 의 다른 주문, 같은 주문의 다른 ID, 대소문자).")
        @Test
        void eventIdNotDerived() {
            assertAll(
                () -> assertThat(reject("43", utf8(replace("\"orderId\":42", "\"orderId\":43"))).getCode())
                    .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT),
                () -> assertThat(reject("42", utf8(replace("order-confirmed:42", "order-confirmed:43"))).getCode())
                    .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT),
                () -> assertThat(reject("42", utf8(replace("order-confirmed:42", "ORDER-CONFIRMED:42"))).getCode())
                    .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT),
                () -> assertThat(reject("42", utf8(replace("order-confirmed:42", "order-confirmed:042"))).getCode())
                    .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT)
            );
        }
    }

    @DisplayName("오류 메시지와 원인에 원문 payload·key·Jackson 오류를 담지 않는다.")
    @Test
    void errorsDoNotCopyRawInput() {
        String secret = "SECRET-PAYLOAD-7731";
        List<PurchaseActivityException> errors = List.of(
            reject("42", utf8("{\"" + secret + "\":")),
            reject("42", utf8(replace("\"orderId\":42,", "\"orderId\":42,\"orderId\":\"" + secret + "\","))),
            reject("KEY-" + secret, utf8(VALID)),
            reject("42", utf8(replace("\"eventType\":\"OrderConfirmed\"", "\"eventType\":\"" + secret + "\""))),
            reject("42", utf8(replace("2026-10-28T07:00:00.123456Z", secret))));

        assertThat(errors).allSatisfy(error -> assertAll(
            () -> assertThat(error.getMessage()).doesNotContain(secret).doesNotContain("KEY-"),
            () -> assertThat(error.getCause()).isNull()
        ));
    }
}
