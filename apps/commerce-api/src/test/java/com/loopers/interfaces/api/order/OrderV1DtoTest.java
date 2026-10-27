package com.loopers.interfaces.api.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderV1DtoTest {

    @DisplayName("확정 요청 DTO 의 toString 은 카드 번호를 가리고 나머지 필드만 보여준다.")
    @Test
    void masksCardNumberInToString() {
        // arrange
        OrderV1Dto.ConfirmRequest request = new OrderV1Dto.ConfirmRequest("k1", "TEST", "0000-0000-0000-0000");

        // act
        String text = request.toString();

        // assert
        assertThat(text).doesNotContain("0000-0000-0000-0000");
        assertThat(text).contains("cardNo=****");
        assertThat(text).contains("k1");
        assertThat(text).contains("TEST");
    }
}