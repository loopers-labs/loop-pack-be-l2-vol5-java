package com.loopers.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCreateCommandTest {

    @DisplayName("PG 생성 요청의 toString 은 카드 번호를 가리고 주문 식별자·카드 종류·금액만 보여준다.")
    @Test
    void masksCardNumberInToString() {
        // arrange
        PaymentCreateCommand command = new PaymentCreateCommand("LP-ORD-0000000042", "TEST", "0000-0000-0000-0000", 7000L);

        // act
        String text = command.toString();

        // assert
        assertThat(text).doesNotContain("0000-0000-0000-0000");
        assertThat(text).contains("cardNo=****");
        assertThat(text).contains("LP-ORD-0000000042");
        assertThat(text).contains("7000");
    }
}