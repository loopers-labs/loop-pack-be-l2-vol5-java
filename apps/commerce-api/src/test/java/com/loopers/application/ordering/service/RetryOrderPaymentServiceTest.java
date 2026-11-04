package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.application.ordering.gateway.PaymentApplicationFailureClassifier;
import com.loopers.application.ordering.support.PaymentApplicationFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryOrderPaymentServiceTest {
    @Test
    @DisplayName("데드락 코드가 있어도 TX rollback 확인이 없으면 재시도 예외를 만들지 않는다")
    void doesNotRetryWithoutRollbackEvidence() {
        var application = mock(ApplyOrderPaymentService.class);
        var classifier = mock(PaymentApplicationFailureClassifier.class);
        var service = new RetryOrderPaymentService(application, classifier);
        var failure = new IllegalStateException("TX 진입 또는 commit 미확인");
        when(application.apply(eq("key"), any())).thenThrow(failure);
        when(classifier.isDeadlock(failure)).thenReturn(true);

        assertThatThrownBy(() -> service.apply("key")).isExactlyInstanceOf(PaymentApplicationFailureException.class)
            .satisfies(thrown -> assertThat(((PaymentApplicationFailureException) thrown).isRollbackConfirmed()).isFalse())
            .hasCause(failure);
    }
}
