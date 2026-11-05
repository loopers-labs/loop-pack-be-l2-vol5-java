package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** O1 중 메시지 identity·고정 type/version·key·payload 검사(설계 W7.2-5). 저장 본문의 엄격한 verifier 는 후속 단위다. */
@DisplayName("OrderOutboxMessage 는 주문 ID 에서 유도한 identity·정규 key·고정 v1 메타데이터·비어 있지 않은 payload 만 허용한다.")
class OrderOutboxMessageTest {

    private static final String PAYLOAD = "{\"eventType\":\"OrderConfirmed\"}";

    private static OrderOutboxMessage message(String eventId, long orderId, String type, int version, String key,
                                              String payload) {
        return new OrderOutboxMessage(eventId, orderId, type, version, key, payload);
    }

    private static Executable rejected(String reason, Runnable creation) {
        return () -> assertThatThrownBy(creation::run).as(reason)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }

    @DisplayName("정상 값은 그대로 보존한다. 최대 long 주문 ID 도 정규 10진 key 로 허용한다.")
    @Test
    void keepsValidValues() {
        OrderOutboxMessage message = message("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD);
        OrderOutboxMessage max = message("order-confirmed:" + Long.MAX_VALUE, Long.MAX_VALUE, "OrderConfirmed", 1,
            Long.toString(Long.MAX_VALUE), PAYLOAD);

        assertAll(
            () -> assertThat(message.eventId()).isEqualTo("order-confirmed:42"),
            () -> assertThat(message.orderId()).isEqualTo(42L),
            () -> assertThat(message.eventType()).isEqualTo(OrderOutboxMessage.EVENT_TYPE),
            () -> assertThat(message.schemaVersion()).isEqualTo(OrderOutboxMessage.SCHEMA_VERSION),
            () -> assertThat(message.messageKey()).isEqualTo("42"),
            () -> assertThat(message.payload()).isEqualTo(PAYLOAD),
            () -> assertThat(max.messageKey()).isEqualTo("9223372036854775807")
        );
    }

    @DisplayName("0 이하 주문 ID 는 거절한다.")
    @Test
    void rejectsNonPositiveOrderId() {
        assertAll(
            rejected("0", () -> message("order-confirmed:0", 0L, "OrderConfirmed", 1, "0", PAYLOAD)),
            rejected("-1", () -> message("order-confirmed:-1", -1L, "OrderConfirmed", 1, "-1", PAYLOAD))
        );
    }

    @DisplayName("eventId 가 없거나 주문 ID 에서 유도한 값과 다르면 거절한다.")
    @Test
    void rejectsUnderivedEventId() {
        assertAll(
            rejected("null", () -> message(null, 42L, "OrderConfirmed", 1, "42", PAYLOAD)),
            rejected("다른 주문", () -> message("order-confirmed:43", 42L, "OrderConfirmed", 1, "42", PAYLOAD)),
            rejected("앞 0", () -> message("order-confirmed:042", 42L, "OrderConfirmed", 1, "42", PAYLOAD)),
            rejected("대소문자", () -> message("Order-Confirmed:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD)),
            rejected("공백", () -> message("order-confirmed:42 ", 42L, "OrderConfirmed", 1, "42", PAYLOAD))
        );
    }

    @DisplayName("사건 타입은 OrderConfirmed, 버전은 1 만 허용한다.")
    @Test
    void rejectsOtherTypeOrVersion() {
        assertAll(
            rejected("null 타입", () -> message("order-confirmed:42", 42L, null, 1, "42", PAYLOAD)),
            rejected("다른 타입", () -> message("order-confirmed:42", 42L, "orderConfirmed", 1, "42", PAYLOAD)),
            rejected("버전 0", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 0, "42", PAYLOAD)),
            rejected("버전 2", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 2, "42", PAYLOAD))
        );
    }

    @DisplayName("key 는 주문 ID 의 정규 10진 문자열이어야 한다.")
    @Test
    void rejectsNonCanonicalKey() {
        assertAll(
            rejected("null", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, null, PAYLOAD)),
            rejected("앞 0", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "042", PAYLOAD)),
            rejected("부호", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "+42", PAYLOAD)),
            rejected("공백", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, " 42", PAYLOAD)),
            rejected("다른 주문", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "43", PAYLOAD))
        );
    }

    @DisplayName("payload 가 null·빈 문자열·공백뿐이면 거절한다.")
    @Test
    void rejectsBlankPayload() {
        assertAll(
            rejected("null", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", null)),
            rejected("빈 문자열", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", "")),
            rejected("공백", () -> message("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", " \n"))
        );
    }

    @DisplayName("거절 메시지에 payload 원문을 담지 않는다.")
    @Test
    void rejectionDoesNotExposePayload() {
        assertThatThrownBy(() -> message("order-confirmed:43", 42L, "OrderConfirmed", 1, "42", "{\"secret\":\"원문\"}"))
            .isInstanceOf(CoreException.class)
            .hasMessageNotContaining("원문").hasMessageNotContaining("secret");
    }
}
