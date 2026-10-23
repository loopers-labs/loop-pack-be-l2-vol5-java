package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorStatusTest {

    @Test
    @DisplayName("이미 처리된 결제 오류는 HTTP 409로 변환한다")
    void mapsAlreadyProcessedPaymentToConflict() {
        assertThat(ErrorStatus.of(ErrorCode.PAYMENT_ALREADY_PROCESSED)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("결제 취소 예정 오류는 HTTP 503으로 변환한다")
    void mapsPaymentCancellationPendingToServiceUnavailable() {
        assertThat(ErrorStatus.of(ErrorCode.PAYMENT_CANCELLATION_PENDING))
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
