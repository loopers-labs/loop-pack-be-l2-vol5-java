package com.loopers.application.payment.port;

import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCreateCommandTest {
    private static final String CARD_NO = "1234-5678-9814-1451";

    @Test
    @DisplayName("결제 생성 명령의 toString에는 카드 번호가 나타나지 않는다")
    void hidesCardNumberInToString() {
        PaymentCreateCommand command = new PaymentCreateCommand("LP-ORD-0000000042", "SAMSUNG", CARD_NO, new Money(5_000));

        assertThat(command.toString())
            .doesNotContain(CARD_NO)
            .doesNotContain("9814")
            .contains("LP-ORD-0000000042");
    }

    @ParameterizedTest(name = "주문 식별자=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "      ", "LP-01"})
    @DisplayName("PG 주문 식별자가 없거나 6자 미만이면 거절하고 메시지에 카드 번호를 담지 않는다")
    void rejectsInvalidOrderId(String providerOrderId) {
        assertThatThrownBy(() -> new PaymentCreateCommand(providerOrderId, "SAMSUNG", CARD_NO, new Money(5_000)))
            .isInstanceOf(IllegalArgumentException.class)
            .message().doesNotContain(CARD_NO);
    }

    @ParameterizedTest(name = "카드 번호=[{0}]")
    @NullSource
    @ValueSource(strings = {"", " "})
    @DisplayName("카드 번호가 없으면 거절한다")
    void rejectsMissingCardNo(String cardNo) {
        assertThatThrownBy(() -> new PaymentCreateCommand("LP-ORD-0000000042", "SAMSUNG", cardNo, new Money(5_000)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
