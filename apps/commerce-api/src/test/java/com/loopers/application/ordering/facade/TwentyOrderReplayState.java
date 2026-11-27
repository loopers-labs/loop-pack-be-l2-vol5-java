package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.mockito.ArgumentCaptor;

record TwentyOrderReplayState(List<TwentyOrderEvidence.PgTransaction> pgTransactions, List<OrderConfirmationEffects> confirmations,
                             long commerceCreateAttempts, long pgCreateRequestsReceived) {
    static TwentyOrderReplayState read(List<TwentyOrderEvidence> rows, List<Long> targetIds, long teaId,
                                       OrderConfirmationEffects.Reader effects, PaymentProviderGateway gateway, Path pgLog) throws IOException {
        var payments = rows.stream().map(TwentyOrderEvidence::pgTransaction).toList();
        var confirmations = targetIds.stream().map(orderId -> effects.read(orderId, teaId)).toList();
        var creates = ArgumentCaptor.forClass(ExpectedPayment.class);
        verify(gateway, atLeastOnce()).create(creates.capture(), any(), any());
        try (var lines = Files.lines(pgLog)) {
            long received = lines.filter(line -> line.endsWith("operation=PG_CREATE_RECEIVED")).count();
            return new TwentyOrderReplayState(payments, confirmations, creates.getAllValues().size(), received);
        }
    }

    void assertRecovered(List<Long> targetIds, long teaId, int expectedTeaStock) {
        assertThat(commerceCreateAttempts).as("Commerce의 PG 생성 호출 누계").isEqualTo(20);
        assertThat(pgCreateRequestsReceived).as("PG가 받은 생성 요청 누계").isEqualTo(20);
        assertThat(pgTransactions).hasSize(20).extracting(TwentyOrderEvidence.PgTransaction::transactionKey).doesNotHaveDuplicates();
        assertThat(pgTransactions).extracting(TwentyOrderEvidence.PgTransaction::orderId).doesNotHaveDuplicates();
        assertThat(pgTransactions).extracting(TwentyOrderEvidence.PgTransaction::status).containsOnly(ProviderPaymentStatus.SUCCESS);
        assertThat(targetIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(confirmations).extracting(OrderConfirmationEffects::orderId).containsExactlyElementsOf(targetIds);
        assertThat(confirmations).allSatisfy(order -> order.assertConfirmed(teaId, expectedTeaStock, 1_000L));
        assertThat(confirmations).extracting(OrderConfirmationEffects::paidRecordId).doesNotHaveDuplicates();
        assertThat(confirmations).extracting(OrderConfirmationEffects::confirmationEventId).doesNotHaveDuplicates();
    }
}
