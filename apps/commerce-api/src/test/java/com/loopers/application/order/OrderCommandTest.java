package com.loopers.application.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCommandTest {

    @DisplayName("확정 커맨드의 toString 은 카드 번호를 가리고 나머지 필드만 보여준다.")
    @Test
    void masksCardNumberInToString() {
        // arrange
        OrderCommand.Confirm command = new OrderCommand.Confirm("k1", "TEST", "0000-0000-0000-0000");

        // act
        String text = command.toString();

        // assert
        assertThat(text).doesNotContain("0000-0000-0000-0000");
        assertThat(text).contains("cardNo=****");
        assertThat(text).contains("k1");
        assertThat(text).contains("TEST");
    }
}