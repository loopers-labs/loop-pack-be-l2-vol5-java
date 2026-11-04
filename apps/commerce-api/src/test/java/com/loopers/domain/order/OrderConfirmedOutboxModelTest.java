package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** O1 중 pending 생성 초깃값·시각 입력 거절과 공개 변경 경계(설계 W7.2-5). W7.3 상태 행동은 후속 단위다. */
@DisplayName("OrderConfirmedOutboxModel.pending 은 검사한 입력으로만 불변 사실·PENDING·초깃값을 함께 구성한다.")
class OrderConfirmedOutboxModelTest {

    private static final OrderOutboxMessage MESSAGE = new OrderOutboxMessage("order-confirmed:42", 42L, "OrderConfirmed", 1,
        "42", "{\"eventType\":\"OrderConfirmed\"}");
    private static final ZonedDateTime FIRST_ATTEMPT_AT = ZonedDateTime.of(2026, 11, 4, 1, 2, 3, 123_456_000, ZoneOffset.UTC);

    private static void assertRejected(OrderOutboxMessage message, ZonedDateTime firstAttemptAt) {
        assertThatThrownBy(() -> OrderConfirmedOutboxModel.pending(message, firstAttemptAt))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }

    @DisplayName("메시지 사실을 복사하고 PENDING·두 시도 횟수 0·최초 시도 시각·오류/발행 시각 null 로 만든다.")
    @Test
    void buildsPendingWithInitialValues() {
        OrderConfirmedOutboxModel outbox = OrderConfirmedOutboxModel.pending(MESSAGE, FIRST_ATTEMPT_AT);

        assertAll(
            () -> assertThat(outbox.getEventId()).isEqualTo("order-confirmed:42"),
            () -> assertThat(outbox.getOrderId()).isEqualTo(42L),
            () -> assertThat(outbox.getEventType()).isEqualTo("OrderConfirmed"),
            () -> assertThat(outbox.getSchemaVersion()).isEqualTo(1),
            () -> assertThat(outbox.getMessageKey()).isEqualTo("42"),
            () -> assertThat(outbox.getPayload()).isEqualTo(MESSAGE.payload()),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getAttemptCount()).isZero(),
            () -> assertThat(outbox.getCycleAttemptCount()).isZero(),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(FIRST_ATTEMPT_AT),
            () -> assertThat(outbox.getLastError()).isNull(),
            () -> assertThat(outbox.getPublishedAt()).isNull(),
            () -> assertThat(outbox.getDeletedAt()).isNull()
        );
    }

    @DisplayName("ZoneId \"UTC\" 처럼 UTC 로 정규화되는 지역도 허용한다.")
    @Test
    void acceptsZoneNormalizedToUtc() {
        ZonedDateTime utcRegion = FIRST_ATTEMPT_AT.withZoneSameInstant(ZoneId.of("UTC"));

        assertThat(OrderConfirmedOutboxModel.pending(MESSAGE, utcRegion).getNextAttemptAt()).isEqualTo(utcRegion);
    }

    @DisplayName("메시지·시각이 없거나, UTC 가 아니거나, 마이크로초 아래 값이 있으면 거절한다.")
    @Test
    void rejectsInvalidInput() {
        assertAll(
            () -> assertRejected(null, FIRST_ATTEMPT_AT),
            () -> assertRejected(MESSAGE, null),
            () -> assertRejected(MESSAGE, FIRST_ATTEMPT_AT.withZoneSameInstant(ZoneOffset.ofHours(9))),
            () -> assertRejected(MESSAGE, FIRST_ATTEMPT_AT.withZoneSameInstant(ZoneId.of("Asia/Seoul"))),
            () -> assertRejected(MESSAGE, FIRST_ATTEMPT_AT.withNano(123_456_789))
        );
    }

    @DisplayName("공개 생성자·setter 가 없어 pending 밖에서 부분 초기화하거나 필드를 바꿀 수 없다.")
    @Test
    void exposesNoPublicMutation() {
        Class<OrderConfirmedOutboxModel> type = OrderConfirmedOutboxModel.class;
        assertAll(
            () -> assertThat(type.getConstructors()).as("public 생성자").isEmpty(),
            () -> assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set")))
                .as("public setter").isEmpty()
        );
    }
}
