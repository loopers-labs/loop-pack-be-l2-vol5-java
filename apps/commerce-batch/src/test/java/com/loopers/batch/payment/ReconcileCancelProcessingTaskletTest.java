package com.loopers.batch.payment;

import com.loopers.batch.payment.PaymentReconciliationRepository.PaymentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcileCancelProcessingTaskletTest {
    @Mock
    private PaymentReconciliationRepository repository;
    @Mock
    private PaymentLookupClient lookupClient;

    @Test
    @DisplayName("CANCEL_PROCESSING 전체를 조회하고 Toss에서 취소 완료된 건만 CANCELED로 기록한다")
    void marksCanceledOnlyWhenTossConfirmsCancellation() {
        PaymentRecord canceled = new PaymentRecord(11L, "LP-ORD-0000000042", "CANCEL_PROCESSING");
        PaymentRecord unresolved = new PaymentRecord(12L, "LP-ORD-0000000043", "CANCEL_PROCESSING");
        when(repository.findCancelProcessingPayments(0, 100)).thenReturn(List.of(canceled, unresolved));
        when(repository.findCancelProcessingPayments(12, 100)).thenReturn(List.of());
        when(lookupClient.findByOrderId(canceled.providerOrderId()))
            .thenReturn(new PaymentLookupClient.LookupResult(200, "CANCELED", "{\"status\":\"CANCELED\"}"));
        when(lookupClient.findByOrderId(unresolved.providerOrderId()))
            .thenReturn(new PaymentLookupClient.LookupResult(200, "DONE", "{\"status\":\"DONE\"}"));

        ReconcileCancelProcessingTasklet tasklet = new ReconcileCancelProcessingTasklet(
            repository, lookupClient, new PaymentStateInterpreter());

        RepeatStatus status = tasklet.execute(null, null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(repository).findCancelProcessingPayments(0, 100);
        verify(lookupClient).findByOrderId(canceled.providerOrderId());
        verify(lookupClient).findByOrderId(unresolved.providerOrderId());
        verify(repository).markCanceled(canceled.id(), "{\"status\":\"CANCELED\"}");
        verify(repository, never()).markCanceled(unresolved.id(), "{\"status\":\"DONE\"}");
    }
}
