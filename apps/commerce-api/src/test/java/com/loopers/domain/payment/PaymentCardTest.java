package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentCardTest {

    @DisplayName("cardType 이 TEST 이고 카드 번호가 허용 목록에 있으면, 만들어진다.")
    @Test
    void createsCard_whenTypeAndNumberAreAllowed() {
        // act
        PaymentCard card = new PaymentCard("TEST", "0000-0000-0000-0000");

        // assert
        assertThat(card.cardType()).isEqualTo("TEST");
        assertThat(card.cardNo()).isEqualTo("0000-0000-0000-0000");
    }

    @DisplayName("cardType 이 TEST 가 아니면, BAD_REQUEST 예외가 발생한다.")
    @Test
    void throwsBadRequest_whenCardTypeIsNotTest() {
        // act
        CoreException result = assertThrows(CoreException.class, () -> {
            new PaymentCard("VISA", "0000-0000-0000-0000");
        });

        // assert
        assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("형식은 맞아도 허용 목록에 없는 카드 번호면, BAD_REQUEST 예외가 발생한다.")
    @Test
    void throwsBadRequest_whenCardNumberIsNotInAllowList() {
        // act
        CoreException result = assertThrows(CoreException.class, () -> {
            new PaymentCard("TEST", "1111-1111-1111-1111");
        });

        // assert
        assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("카드 번호가 없으면, BAD_REQUEST 예외가 발생한다.")
    @Test
    void throwsBadRequest_whenCardNumberIsMissing() {
        // act
        CoreException result = assertThrows(CoreException.class, () -> {
            new PaymentCard("TEST", null);
        });

        // assert
        assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("toString 은 카드 번호를 가린다.")
    @Test
    void masksCardNumberInToString() {
        // act
        String text = new PaymentCard("TEST", "0000-0000-0000-0000").toString();

        // assert
        assertThat(text).doesNotContain("0000-0000-0000-0000");
        assertThat(text).contains("cardNo=****");
    }
}