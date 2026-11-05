package com.loopers.application.outbox;

import com.loopers.application.outbox.OutboxBackfillJudge.Decision;
import com.loopers.application.outbox.OutboxBackfillJudge.OrderFacts;
import com.loopers.application.outbox.OutboxBackfillJudge.RowFacts;
import com.loopers.domain.order.OrderStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 복구 판단(docs/week7/decisions.md D-5, D-14). DB 없이 주문 사실과 기존 행을 넣고 결과만 본다 — 행 없음 → 새 행 /
 * 같은 행 → 아무것도 안 함 / 다른 행 → 오류 / 사실 모자람 → 오류. 6주차 재현 E2E는 check 밖이라, 7주차 복구 코드를 고칠 때마다
 * 이 테스트가 판단을 지킨다.
 */
class OutboxBackfillTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.of(2026, 11, 6, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
    private static final String PAYLOAD = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:7\","
        + "\"orderId\":7,\"confirmedAt\":\"2026-11-06T01:02:03.123456Z\",\"items\":[{\"productId\":7,\"quantity\":2},"
        + "{\"productId\":8,\"quantity\":1}]}";

    private static RowFacts candidate() {
        return new RowFacts("order-confirmed:v1:7", 7L, "OrderConfirmed", 1, "7", PAYLOAD);
    }

    static Stream<Arguments> differentRows() {
        RowFacts base = candidate();
        return Stream.of(
            Arguments.of("eventId", new RowFacts("order-confirmed:v1:70", 7L, base.eventType(), 1, base.messageKey(), base.payload())),
            Arguments.of("orderId", new RowFacts(base.eventId(), 70L, base.eventType(), 1, base.messageKey(), base.payload())),
            Arguments.of("eventType", new RowFacts(base.eventId(), 7L, "OrderCancelled", 1, base.messageKey(), base.payload())),
            Arguments.of("schemaVersion", new RowFacts(base.eventId(), 7L, base.eventType(), 2, base.messageKey(), base.payload())),
            Arguments.of("messageKey", new RowFacts(base.eventId(), 7L, base.eventType(), 1, "70", base.payload())),
            // 공백 하나만 달라도 바이트가 다르다(INV-702) — 의미가 같아도 같은 행으로 보지 않는다
            Arguments.of("payload", new RowFacts(base.eventId(), 7L, base.eventType(), 1, base.messageKey(),
                base.payload().replace("\"quantity\":2", "\"quantity\": 2"))),
            Arguments.of("payload", new RowFacts(base.eventId(), 7L, base.eventType(), 1, base.messageKey(),
                base.payload().replace("\"quantity\":1", "\"quantity\":3")))
        );
    }

    @DisplayName("행 판단")
    @Nested
    class Decide {

        @DisplayName("같은 eventId의 행이 없으면 새 행을 만든다.")
        @Test
        void noRow_creates() {
            // act
            Decision decision = OutboxBackfillJudge.decide(candidate(), Optional.empty());

            // assert
            assertThat(decision).isEqualTo(Decision.CREATE);
        }

        @DisplayName("여섯 칸(eventId·orderId·eventType·schemaVersion·messageKey·payload)이 글자까지 같은 행이 있으면 아무것도 하지 않는다. "
            + "(주문 B — 같은 payload가 다시 가서 아무 일도 없이 끝난다)")
        @Test
        void sameRow_doesNothing() {
            // arrange — 같은 값이지만 다른 문자열 인스턴스로 만들어 참조가 아니라 내용을 비교하는지 본다
            RowFacts existing = new RowFacts(new String("order-confirmed:v1:7"), 7L, new String("OrderConfirmed"), 1,
                new String("7"), new String(PAYLOAD));

            // act
            Decision decision = OutboxBackfillJudge.decide(candidate(), Optional.of(existing));

            // assert
            assertThat(decision).isEqualTo(Decision.ALREADY_PRESENT_SAME);
        }

        @DisplayName("한 칸이라도 다른 행이 있으면 409로 거절하고, 어느 칸이 다른지 알려 준다. payload 내용은 메시지에 싣지 않는다. (INV-706)")
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.loopers.application.outbox.OutboxBackfillTest#differentRows")
        void differentRow_isConflict(String field, RowFacts existing) {
            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> OutboxBackfillJudge.decide(candidate(), Optional.of(existing)));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).contains(field + "("),
                () -> assertThat(exception.getMessage()).doesNotContain("\"items\"", "\"confirmedAt\"")
            );
        }
    }

    @DisplayName("주문 사실 점검")
    @Nested
    class RequireStoredFacts {

        @DisplayName("CONFIRMED이고 confirmed_at과 품목이 있으면 통과한다.")
        @Test
        void confirmedWithFacts_passes() {
            assertThatCode(() -> OutboxBackfillJudge.requireStoredFacts(new OrderFacts(7L, OrderStatus.CONFIRMED, CONFIRMED_AT, 2)))
                .doesNotThrowAnyException();
        }

        @DisplayName("confirmed_at이 없으면 추측해서 채우지 않고 409로 거절한다. (D-5)")
        @Test
        void noConfirmedAt_isConflict() {
            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> OutboxBackfillJudge.requireStoredFacts(new OrderFacts(7L, OrderStatus.CONFIRMED, null, 2)));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).contains("confirmed_at")
            );
        }

        @DisplayName("CONFIRMED가 아닌 주문은 409로 거절한다 — 확정되지 않은 주문의 확정 이벤트를 만들지 않는다.")
        @ParameterizedTest(name = "[{index}] {0}")
        @EnumSource(value = OrderStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
        void notConfirmed_isConflict(OrderStatus status) {
            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> OutboxBackfillJudge.requireStoredFacts(new OrderFacts(7L, status, null, 2)));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).contains("CONFIRMED가 아님", status.name())
            );
        }

        @DisplayName("저장된 품목이 없으면 409로 거절한다.")
        @Test
        void noItems_isConflict() {
            // act
            CoreException exception = assertThrows(CoreException.class,
                () -> OutboxBackfillJudge.requireStoredFacts(new OrderFacts(7L, OrderStatus.CONFIRMED, CONFIRMED_AT, 0)));

            // assert
            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).contains("품목")
            );
        }
    }
}
