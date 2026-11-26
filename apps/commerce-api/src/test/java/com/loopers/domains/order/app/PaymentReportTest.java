package com.loopers.domains.order.app;

import com.loopers.common.domain.Money;
import com.loopers.domains.order.domain.Order;
import com.loopers.domains.payment.app.port.PaymentTransaction;
import com.loopers.domains.payment.app.port.PaymentTransactionStatus;
import com.loopers.domains.payment.domain.PaymentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReportTest {
    @Test
    @DisplayName("PG 거래 상태 CANCELED는 돈이 나가지 않은 확정 결과라 FAILED 보고와 실패 결과로 바꾸고 거래 키를 유지한다")
    void mapsCanceledToFailedOutcome() {
        PaymentReport report = PaymentReport.from(transaction(PaymentTransactionStatus.CANCELED));

        assertThat(report).isEqualTo(new PaymentReport(PaymentRecord.ProviderStatus.FAILED, "TR-1"));
        assertThat(report.outcome()).isEqualTo(Order.PaymentOutcome.FAILED);
    }

    @ParameterizedTest(name = "PG 거래 상태={0}")
    @CsvSource({"PENDING, PENDING, UNKNOWN", "SUCCESS, SUCCESS, SUCCEEDED", "FAILED, FAILED, FAILED"})
    @DisplayName("기존 PG 거래 상태는 같은 이름의 보고와 기존 결과로 바꾼다")
    void keepsExistingMappings(PaymentTransactionStatus status, PaymentRecord.ProviderStatus reported,
        Order.PaymentOutcome outcome) {
        PaymentReport report = PaymentReport.from(transaction(status));

        assertThat(report).isEqualTo(new PaymentReport(reported, "TR-1"));
        assertThat(report.outcome()).isEqualTo(outcome);
    }

    private static PaymentTransaction transaction(PaymentTransactionStatus status) {
        return new PaymentTransaction("TR-1", "LP-ORD-0000000042", new Money(7000), status);
    }
}
