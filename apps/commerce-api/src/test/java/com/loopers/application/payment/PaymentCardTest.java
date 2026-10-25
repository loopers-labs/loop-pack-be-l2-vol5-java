package com.loopers.application.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCardTest {

    @DisplayName("카드 종류와 번호가 공백만이 아닌 문자열이면 형식·길이를 검사하지 않고 그대로 받는다.")
    @Test
    void acceptsNonBlankCardInputs() {
        PaymentCard card = new PaymentCard("ANY", "x");

        assertThat(card.cardType()).isEqualTo("ANY");
        assertThat(card.cardNo()).isEqualTo("x");
    }

    @DisplayName("카드 종류나 번호가 없거나 공백만이면 400으로 거절한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingOrBlankInputs(String value) {
        assertBadRequest(() -> new PaymentCard(value, "0000-0000-0000-0000"));
        assertBadRequest(() -> new PaymentCard("TEST", value));
    }

    @DisplayName("문자열 표현에 카드 값을 드러내지 않는다.")
    @Test
    void hidesCardValuesFromString() {
        assertThat(new PaymentCard("SECRET-TYPE", "1234-5678").toString())
            .doesNotContain("SECRET-TYPE").doesNotContain("1234-5678");
    }

    private void assertBadRequest(Runnable creation) {
        assertThatThrownBy(creation::run)
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }
}
