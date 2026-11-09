package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgPaymentRequestTest {

    private static final String CARD_NO = "0000-0000-0000-0000";

    @DisplayName("PG 결제 요청을 만들 때, ")
    @Nested
    class Create {
        @DisplayName("금액이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class,
                () -> new PgPaymentRequest("LP-ORD-0000000042", "TEST", CARD_NO, 0));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("주문 번호·카드 종류·카드 번호 중 하나라도 비어 있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenRequiredValueIsBlank() {
            // act
            CoreException noOrder = assertThrows(CoreException.class, () -> new PgPaymentRequest(" ", "TEST", CARD_NO, 7000));
            CoreException noType = assertThrows(CoreException.class, () -> new PgPaymentRequest("LP-ORD-0000000042", null, CARD_NO, 7000));
            CoreException noCard = assertThrows(CoreException.class, () -> new PgPaymentRequest("LP-ORD-0000000042", "TEST", "", 7000));

            // assert
            assertThat(noOrder.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(noType.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(noCard.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("문자열로 바꾸면, 카드 번호를 가리고 나머지 값은 보여 준다.")
    @Test
    void masksCardNumberInToString() {
        // arrange
        PgPaymentRequest request = new PgPaymentRequest("LP-ORD-0000000042", "TEST", CARD_NO, 7000);

        // act
        String text = request.toString();

        // assert
        assertThat(text).doesNotContain(CARD_NO).contains("cardNo=****", "LP-ORD-0000000042", "7000");
    }
}
