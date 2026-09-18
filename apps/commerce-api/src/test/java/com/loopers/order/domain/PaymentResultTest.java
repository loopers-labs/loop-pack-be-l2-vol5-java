package com.loopers.order.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentResultTest {

    @DisplayName("[R-ORDER-12] 주문 확정 결과에는 결제액과 결제 결과가 남는다.")
    @Nested
    class RequiredPaymentResult {

        @DisplayName("[동등 클래스 분할] 결제액과 결제 시점이 모두 있으면 결제 결과를 만든다.")
        @Test
        void createsPaymentResult_whenAmountAndPaidAtAreProvided() {
            ZonedDateTime paidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

            PaymentResult result = new PaymentResult(7_000L, paidAt);

            assertAll(
                () -> assertThat(result.amount()).isEqualTo(7_000L),
                () -> assertThat(result.paidAt()).isEqualTo(paidAt)
            );
        }

        @DisplayName("[오류 추측] 결제액이 없으면 내부 오류로 거절한다.")
        @Test
        void throwsInternalError_whenAmountIsNull() {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new PaymentResult(null, ZonedDateTime.now())
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @DisplayName("[P-ORDER-06] 결제 결과에는 결제액과 결제 시점이 포함된다.")
    @Nested
    class PaymentResultFields {

        @DisplayName("[오류 추측] 결제 시점이 없으면 내부 오류로 거절한다.")
        @Test
        void throwsInternalError_whenPaidAtIsNull() {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new PaymentResult(7_000L, null)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }
}
