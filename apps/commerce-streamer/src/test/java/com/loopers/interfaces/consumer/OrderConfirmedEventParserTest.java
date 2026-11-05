package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import com.loopers.interfaces.consumer.OrderEventParseResult.Invalid;
import com.loopers.interfaces.consumer.OrderEventParseResult.Parsed;
import com.loopers.interfaces.consumer.OrderEventParseResult.Reason;
import com.loopers.interfaces.consumer.OrderEventParseResult.Unsupported;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.loopers.support.OrderConfirmedMessages.bytes;
import static com.loopers.support.OrderConfirmedMessages.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class OrderConfirmedEventParserTest {

    private final OrderConfirmedEventParser parser = new OrderConfirmedEventParser();

    @DisplayName("정상 이벤트")
    @Nested
    class Valid {

        @DisplayName("v1 이벤트는 모르는 필드를 무시하고 주문·확정 시각(UTC)·품목으로 읽는다")
        @Test
        void ignoresUnknownFields() {
            String payload = """
                {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"evt-42","orderId":42,
                 "confirmedAt":"2026-10-29T10:00:00+09:00","couponCode":"NEW",
                 "items":[{"productId":7,"quantity":2,"color":"red"},{"productId":8,"quantity":1}]}""";

            OrderEventParseResult result = parser.parse("42", bytes(payload));

            assertThat(result).isEqualTo(new Parsed(new OrderConfirmedEvent(
                "evt-42", 42L, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new PurchasedItem(7L, 2), new PurchasedItem(8L, 1))
            )));
        }

        @DisplayName("JSON 속성 순서와 items 순서가 달라도 지문이 같고, 수량이 다르면 지문이 다르다")
        @Test
        void fingerprintIgnoresOrdering() {
            String reordered = """
                {"items":[{"quantity":1,"productId":8},{"quantity":2,"productId":7}],"orderId":42,
                 "confirmedAt":"2026-10-29T01:00:00Z","eventId":"evt-42","schemaVersion":1,"eventType":"OrderConfirmed"}""";

            String original = fingerprintOf(json("evt-42", 42, 7, 2, 8, 1));
            String sameFact = fingerprintOf(reordered);
            String otherQuantity = fingerprintOf(json("evt-42", 42, 7, 5, 8, 1));

            assertThat(sameFact).isEqualTo(original);
            assertThat(otherQuantity).isNotEqualTo(original);
        }

        private String fingerprintOf(String payload) {
            OrderEventParseResult result = parser.parse("42", bytes(payload));
            assertThat(result).isInstanceOf(Parsed.class);
            return ((Parsed) result).event().fingerprint();
        }
    }

    @DisplayName("받는 쪽을 고치면 처리할 수 있는 형식")
    @Nested
    class UnsupportedFormat {

        @DisplayName("처음 보는 schemaVersion 2는 지원하지 않는 형식이다")
        @Test
        void unknownSchemaVersion() {
            OrderEventParseResult result = parser.parse("42", bytes(json(2, "evt-42", 42, 7, 2)));

            assertThat(result).isInstanceOf(Unsupported.class);
        }

        @DisplayName("OrderConfirmed가 아닌 eventType은 지원하지 않는 형식이다")
        @Test
        void unknownEventType() {
            String payload = json("evt-42", 42, 7, 2).replace("OrderConfirmed", "OrderCancelled");

            OrderEventParseResult result = parser.parse("42", bytes(payload));

            assertThat(result).isInstanceOf(Unsupported.class);
        }
    }

    @DisplayName("고칠 수 없는 형식 오류")
    @Nested
    class PermanentFormatError {

        @DisplayName("record 내용이 틀리면 이유와 함께 영구 형식 오류로 돌려준다")
        @ParameterizedTest(name = "[{index}] {0} → {3}")
        @MethodSource("com.loopers.interfaces.consumer.OrderConfirmedEventParserTest#invalidPayloads")
        void invalidPayload(String scene, String key, String payload, Reason expected) {
            OrderEventParseResult result = parser.parse(key, bytes(payload));

            assertThat(result).isInstanceOf(Invalid.class);
            assertThat(((Invalid) result).reason()).isEqualTo(expected);
        }

        @DisplayName("빈 값(null payload)은 깨진 JSON으로 본다")
        @Test
        void nullPayload() {
            OrderEventParseResult result = parser.parse("42", null);

            assertThat(result).isInstanceOf(Invalid.class);
            assertThat(((Invalid) result).reason()).isEqualTo(Reason.MALFORMED_JSON);
        }

        @DisplayName("형식 오류여도 읽을 수 있는 eventId·orderId는 이상 기록용으로 돌려준다")
        @Test
        void keepsIdentifiersForAnomaly() {
            OrderEventParseResult result = parser.parse("99", bytes(json("evt-42", 42, 7, 2)));

            assertThat(result).isInstanceOf(Invalid.class);
            Invalid invalid = (Invalid) result;
            assertThat(invalid.reason()).isEqualTo(Reason.KEY_MISMATCH);
            assertThat(invalid.eventId()).isEqualTo("evt-42");
            assertThat(invalid.orderId()).isEqualTo(42L);
        }
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
            arguments("깨진 JSON", "42", "{\"eventType\":\"OrderConfirmed\",", Reason.MALFORMED_JSON),
            arguments("객체가 아닌 JSON", "42", "[1,2]", Reason.MALFORMED_JSON),
            arguments("eventId 누락", "42", json("evt-42", 42, 7, 2).replace("\"eventId\":\"evt-42\",", ""), Reason.MISSING_FIELD),
            arguments("eventType 누락", "42", json("evt-42", 42, 7, 2).replace("\"eventType\":\"OrderConfirmed\",", ""),
                Reason.MISSING_FIELD),
            arguments("schemaVersion 누락", "42", json("evt-42", 42, 7, 2).replace("\"schemaVersion\":1,", ""),
                Reason.MISSING_FIELD),
            arguments("confirmedAt 누락", "42",
                json("evt-42", 42, 7, 2).replace("\"confirmedAt\":\"2026-10-29T10:00:00+09:00\",", ""), Reason.MISSING_FIELD),
            arguments("quantity 누락", "42", json("evt-42", 42, 7, 2).replace(",\"quantity\":2", ""), Reason.MISSING_FIELD),
            arguments("confirmedAt 형식 오류", "42", json("evt-42", 42, 7, 2).replace("2026-10-29T10:00:00+09:00", "어제"),
                Reason.INVALID_FIELD),
            arguments("quantity 소수", "42", json("evt-42", 42, 7, 2).replace("\"quantity\":2", "\"quantity\":1.5"),
                Reason.INVALID_FIELD),
            arguments("items 비어 있음", "42", json("evt-42", 42), Reason.EMPTY_ITEMS),
            arguments("수량 0", "42", json("evt-42", 42, 7, 0), Reason.NON_POSITIVE_QUANTITY),
            arguments("수량 음수", "42", json("evt-42", 42, 7, -1), Reason.NON_POSITIVE_QUANTITY),
            arguments("items 안 같은 상품 중복", "42", json("evt-42", 42, 7, 2, 7, 1), Reason.DUPLICATE_PRODUCT),
            arguments("key와 orderId 불일치", "43", json("evt-42", 42, 7, 2), Reason.KEY_MISMATCH),
            arguments("key 없음", null, json("evt-42", 42, 7, 2), Reason.KEY_MISMATCH)
        );
    }
}
