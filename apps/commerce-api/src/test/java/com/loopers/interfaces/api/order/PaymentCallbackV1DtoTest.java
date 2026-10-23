package com.loopers.interfaces.api.order;

import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** PG callback 본문 → 결제 결과. */
class PaymentCallbackV1DtoTest {

    @DisplayName("status 가 PENDING·SUCCESS·FAILED 면 그대로, 그 밖(소문자·모르는 값·없음)은 UNKNOWN. 거래 키·금액은 그대로 옮긴다.")
    @ParameterizedTest
    @CsvSource(value = {"PENDING, PENDING", "SUCCESS, SUCCESS", "FAILED, FAILED", "success, UNKNOWN", "CANCELED, UNKNOWN", "NULL, UNKNOWN"}, nullValues = "NULL")
    void toResult_mapsStatus(String status, Outcome expected) {
        PaymentGatewayResult result = new PaymentCallbackV1Dto.CallbackRequest("tx-1", "ORDER-1", 3_000L, status).toResult();

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.transactionKey()).isEqualTo("tx-1");
        assertThat(result.amount()).isEqualTo(3_000L);
    }
}
