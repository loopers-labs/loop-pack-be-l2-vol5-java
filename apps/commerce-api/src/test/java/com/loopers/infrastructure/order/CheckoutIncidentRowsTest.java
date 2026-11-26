package com.loopers.infrastructure.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("W10 주문별 행 대조 검증")
class CheckoutIncidentRowsTest {

    private static final long A = 1L;
    private static final long B = 2L;

    @DisplayName("주문마다 상품 A·B 한 행씩 기대값을 만든다.")
    @Test
    void buildsExpectedRows() {
        assertThat(CheckoutIncidentScenario.expectedRows(List.of(11L, 12L), A, B, 2, 1))
            .containsExactly("11:1:2", "11:2:1", "12:1:2", "12:2:1");
    }

    @DisplayName("행 순서만 다르면 통과한다.")
    @Test
    void acceptsSameRowsInAnyOrder() {
        List<String> expected = CheckoutIncidentScenario.expectedRows(List.of(11L, 12L), A, B, 2, 1);

        assertThatCode(() -> CheckoutIncidentScenario.assertExactRows("활동",
            List.of("12:2:1", "11:1:2", "12:1:2", "11:2:1"), expected)).doesNotThrowAnyException();
    }

    @DisplayName("합계는 같아도 주문별 수량이 어긋나거나 중복·누락·예상 밖 행이 있으면 거절한다.")
    @Test
    void rejectsOffsettingAndUnexpectedRows() {
        List<String> expected = CheckoutIncidentScenario.expectedRows(List.of(11L, 12L), A, B, 2, 1);

        assertAll(
            () -> assertThatThrownBy(() -> CheckoutIncidentScenario.assertExactRows("상쇄",
                List.of("11:1:3", "11:2:1", "12:1:1", "12:2:1"), expected)).isInstanceOf(AssertionError.class),
            () -> assertThatThrownBy(() -> CheckoutIncidentScenario.assertExactRows("중복·누락",
                List.of("11:1:2", "11:1:2", "11:2:1", "12:2:1"), expected)).isInstanceOf(AssertionError.class),
            () -> assertThatThrownBy(() -> CheckoutIncidentScenario.assertExactRows("예상 밖",
                List.of("11:1:2", "11:2:1", "12:1:2", "12:2:1", "13:1:2"), expected)).isInstanceOf(AssertionError.class)
        );
    }

    @DisplayName("예약은 변경량 크기가 같아도 감소 방향이 아니면 거절한다.")
    @Test
    void rejectsReservationWithWrongDirection() {
        List<String> expected = CheckoutIncidentScenario.expectedRows(List.of(11L), A, B, "2:2", "1:1");

        assertAll(
            () -> assertThatCode(() -> CheckoutIncidentScenario.assertExactRows("예약", List.of("11:1:2:2", "11:2:1:1"),
                expected)).doesNotThrowAnyException(),
            () -> assertThatThrownBy(() -> CheckoutIncidentScenario.assertExactRows("예약", List.of("11:1:2:-2", "11:2:1:1"),
                expected)).isInstanceOf(AssertionError.class)
        );
    }
}
