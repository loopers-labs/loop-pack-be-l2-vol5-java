package com.loopers.domain.outbox;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Outbox 행이 스스로 지키는 규칙(docs/week7/decisions.md D-1, D-12): 만들 때의 필수 값, payload 불변(INV-702),
 * PENDING에서만 PUBLISHED(INV-703), 재개는 같은 행을 PENDING으로(INV-705).
 */
class OutboxEventTest {

    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 11, 5, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:v1:42\"}";

    private static OutboxEvent pending() {
        return OutboxEvent.pending("order-confirmed:v1:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD, T0);
    }

    private static OutboxEvent blocked() {
        OutboxEvent event = pending();
        event.block("broker 없음");
        return event;
    }

    private static OutboxEvent published() {
        OutboxEvent event = pending();
        event.markPublished(T0.plusSeconds(1));
        return event;
    }

    @DisplayName("만들기")
    @Nested
    class Create {

        @DisplayName("PENDING, 시도 0회, 첫 시도 시각 = 받은 시각, 발행 시각·오류 없음으로 만들어지고 받은 값을 그대로 담는다.")
        @Test
        void initialState() {
            // act
            OutboxEvent event = pending();

            // assert
            assertAll(
                () -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(event.getAttemptCount()).isZero(),
                () -> assertThat(event.getNextAttemptAt()).isEqualTo(T0),
                () -> assertThat(event.getPublishedAt()).isNull(),
                () -> assertThat(event.getLastError()).isNull(),
                () -> assertThat(event.getEventId()).isEqualTo("order-confirmed:v1:42"),
                () -> assertThat(event.getOrderId()).isEqualTo(42L),
                () -> assertThat(event.getEventType()).isEqualTo("OrderConfirmed"),
                () -> assertThat(event.getSchemaVersion()).isEqualTo(1),
                () -> assertThat(event.getMessageKey()).isEqualTo("42"),
                () -> assertThat(event.getPayload()).isEqualTo(PAYLOAD)
            );
        }

        @DisplayName("eventId가 비어 있으면 BAD_REQUEST다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsBlankEventId(String eventId) {
            assertBadRequest(() -> OutboxEvent.pending(eventId, 42L, "OrderConfirmed", 1, "42", PAYLOAD, T0));
        }

        @DisplayName("eventType이 비어 있으면 BAD_REQUEST다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsBlankEventType(String eventType) {
            assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", 42L, eventType, 1, "42", PAYLOAD, T0));
        }

        @DisplayName("messageKey가 비어 있으면 BAD_REQUEST다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsBlankMessageKey(String messageKey) {
            assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", 42L, "OrderConfirmed", 1, messageKey, PAYLOAD, T0));
        }

        @DisplayName("payload가 비어 있으면 BAD_REQUEST다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsBlankPayload(String payload) {
            assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", 42L, "OrderConfirmed", 1, "42", payload, T0));
        }

        @DisplayName("orderId·첫 시도 시각이 없거나 schemaVersion이 양수가 아니면 BAD_REQUEST다.")
        @Test
        void rejectsMissingOrderIdTimeOrVersion() {
            assertAll(
                () -> assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", null, "OrderConfirmed", 1, "42", PAYLOAD, T0)),
                () -> assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD, null)),
                () -> assertBadRequest(() -> OutboxEvent.pending("order-confirmed:v1:42", 42L, "OrderConfirmed", 0, "42", PAYLOAD, T0))
            );
        }
    }

    @DisplayName("payload 불변(INV-702)")
    @Nested
    class Immutability {

        @DisplayName("eventId·orderId·eventType·schemaVersion·messageKey·payload를 바꾸는 public 메서드가 없다.")
        @Test
        void noSettersForFixedFields() {
            // act
            String[] setters = Arrays.stream(OutboxEvent.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toArray(String[]::new);

            // assert
            assertThat(setters).isEmpty();
        }

        @DisplayName("실패 기록·BLOCKED·재개를 거쳐도 eventId·payload·messageKey는 처음 그대로다.")
        @Test
        void transitionsKeepPayload() {
            // arrange
            OutboxEvent event = pending();

            // act
            event.recordFailure("타임아웃", T0.plusSeconds(1));
            event.block("24시간 경과");
            event.resume(T0.plusDays(2));

            // assert
            assertAll(
                () -> assertThat(event.getEventId()).isEqualTo("order-confirmed:v1:42"),
                () -> assertThat(event.getPayload()).isEqualTo(PAYLOAD),
                () -> assertThat(event.getMessageKey()).isEqualTo("42"),
                () -> assertThat(event.getOrderId()).isEqualTo(42L)
            );
        }
    }

    @DisplayName("PUBLISHED로 바꾸기(INV-703)")
    @Nested
    class MarkPublished {

        @DisplayName("PENDING이면 PUBLISHED가 되고 발행 시각이 남는다.")
        @Test
        void fromPending() {
            // arrange
            OutboxEvent event = pending();

            // act
            event.markPublished(T0.plusSeconds(3));

            // assert
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(event.getPublishedAt()).isEqualTo(T0.plusSeconds(3));
        }

        @DisplayName("이미 PUBLISHED이거나 BLOCKED면 CONFLICT이고 상태는 그대로다.")
        @Test
        void rejectsFromPublishedOrBlocked() {
            // arrange
            OutboxEvent published = published();
            OutboxEvent blocked = blocked();

            // act & assert
            assertConflict(() -> published.markPublished(T0.plusSeconds(9)));
            assertConflict(() -> blocked.markPublished(T0.plusSeconds(9)));
            assertThat(published.getPublishedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(blocked.getStatus()).isEqualTo(OutboxStatus.BLOCKED);
            assertThat(blocked.getPublishedAt()).isNull();
        }
    }

    @DisplayName("실패 기록")
    @Nested
    class RecordFailure {

        @DisplayName("PENDING이면 상태는 PENDING 그대로, 오류와 다음 시도 시각만 바뀐다.")
        @Test
        void fromPending() {
            // arrange
            OutboxEvent event = pending();

            // act
            event.recordFailure("TimeoutException", T0.plusSeconds(2));

            // assert
            assertAll(
                () -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(event.getLastError()).isEqualTo("TimeoutException"),
                () -> assertThat(event.getNextAttemptAt()).isEqualTo(T0.plusSeconds(2)),
                () -> assertThat(event.getAttemptCount()).isZero()
            );
        }

        @DisplayName("오류가 1000자를 넘으면 1000자로 잘라 남긴다.")
        @Test
        void truncatesLongError() {
            // arrange
            OutboxEvent event = pending();

            // act
            event.recordFailure("x".repeat(OutboxEvent.MAX_ERROR_LENGTH + 500), T0.plusSeconds(2));

            // assert
            assertThat(event.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);
        }

        @DisplayName("PUBLISHED·BLOCKED면 CONFLICT다.")
        @Test
        void rejectsFromPublishedOrBlocked() {
            assertConflict(() -> published().recordFailure("e", T0.plusSeconds(2)));
            assertConflict(() -> blocked().recordFailure("e", T0.plusSeconds(2)));
        }
    }

    @DisplayName("BLOCKED로 빼기")
    @Nested
    class Block {

        @DisplayName("PENDING이면 BLOCKED가 되고 오류를 남긴다(잘라서).")
        @Test
        void fromPending() {
            // arrange
            OutboxEvent event = pending();

            // act
            event.block("y".repeat(OutboxEvent.MAX_ERROR_LENGTH + 1));

            // assert
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.BLOCKED);
            assertThat(event.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);
        }

        @DisplayName("PUBLISHED·BLOCKED면 CONFLICT다.")
        @Test
        void rejectsFromPublishedOrBlocked() {
            assertConflict(() -> published().block("e"));
            assertConflict(() -> blocked().block("e"));
        }
    }

    @DisplayName("재개(INV-705)")
    @Nested
    class Resume {

        @DisplayName("BLOCKED면 같은 행이 PENDING으로 돌아가고 다음 시도 시각이 바뀐다. 마지막 오류는 남는다.")
        @Test
        void fromBlocked() {
            // arrange
            OutboxEvent event = blocked();

            // act
            event.resume(T0.plusDays(2));

            // assert
            assertAll(
                () -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(event.getNextAttemptAt()).isEqualTo(T0.plusDays(2)),
                () -> assertThat(event.getLastError()).isEqualTo("broker 없음"),
                () -> assertThat(event.getPublishedAt()).isNull()
            );
        }

        @DisplayName("PENDING·PUBLISHED면 CONFLICT다.")
        @Test
        void rejectsFromPendingOrPublished() {
            assertConflict(() -> pending().resume(T0.plusDays(2)));
            assertConflict(() -> published().resume(T0.plusDays(2)));
        }
    }

    private static void assertBadRequest(Executable executable) {
        CoreException exception = assertThrows(CoreException.class, executable);
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    private static void assertConflict(Executable executable) {
        CoreException exception = assertThrows(CoreException.class, executable);
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
    }
}
