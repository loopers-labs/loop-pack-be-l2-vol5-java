package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.domain.coupon.repository.CouponIssueCounter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CountingIssueCouponUseCaseTest {
    private final IssueCouponFacade issuance = mock(IssueCouponFacade.class);
    private final CouponIssueCounter counter = mock(CouponIssueCounter.class);
    private final CountingIssueCouponUseCase service = new CountingIssueCouponUseCase(issuance, counter);
    private final CouponIssueCommand command = new CouponIssueCommand(1L, 2L);

    @Test
    void aggregateFailurePreservesConfirmedIssueResult() {
        CouponIssueResult issued = new CouponIssueResult(3L, 1L, Instant.now(), "saved benefit", 1000L, true);
        when(issuance.execute(command)).thenReturn(issued);
        when(counter.recordIssued(1L, 3L)).thenThrow(new IllegalStateException("redis unavailable"));

        assertThat(service.execute(command)).isSameAs(issued);
        verify(counter).recordIssued(1L, 3L);
    }

    @Test
    void failedOrUnconfirmedIssueNeverReachesCounter() {
        when(issuance.execute(command)).thenThrow(new IllegalStateException("transaction not confirmed"));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(counter);
    }
}
