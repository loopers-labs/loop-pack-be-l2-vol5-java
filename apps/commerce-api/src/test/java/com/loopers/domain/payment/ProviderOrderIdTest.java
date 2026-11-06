package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderOrderIdTest {

    @DisplayName("내부 주문 id를 LP-ORD- 뒤에 10자리로 0을 채운 PG 주문 번호로 바꾼다.")
    @Test
    void convertsOrderIdToZeroPaddedProviderOrderId() {
        // act & assert
        assertThat(ProviderOrderId.from(42L)).isEqualTo("LP-ORD-0000000042");
        assertThat(ProviderOrderId.from(1L)).isEqualTo("LP-ORD-0000000001");
    }

    @DisplayName("PG 주문 번호는 PG 계약의 최소 길이(6자)를 넘는다.")
    @Test
    void isLongerThanContractMinimum() {
        // act & assert
        assertThat(ProviderOrderId.from(1L).length()).isGreaterThanOrEqualTo(6);
    }

    @DisplayName("주문 id가 없거나 0 이하이면, BAD_REQUEST 예외가 발생한다.")
    @Test
    void throwsBadRequestException_whenOrderIdIsNotPositive() {
        // act
        CoreException nullId = assertThrows(CoreException.class, () -> ProviderOrderId.from(null));
        CoreException zero = assertThrows(CoreException.class, () -> ProviderOrderId.from(0L));

        // assert
        assertThat(nullId.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(zero.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }
}
