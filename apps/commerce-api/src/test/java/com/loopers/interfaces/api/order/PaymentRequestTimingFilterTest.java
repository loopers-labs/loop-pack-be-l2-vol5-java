package com.loopers.interfaces.api.order;

import com.loopers.application.order.PaymentConfirmationTiming;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentRequestTimingFilterTest {
    private final PaymentConfirmationTiming timing = mock(PaymentConfirmationTiming.class);

    @Test
    void capturesEntryBeforeTheRestOfTheHttpChainAndDoesNotResetAnExistingStart() throws Exception {
        var filter = new PaymentRequestTimingFilter(timing);
        var request = new MockHttpServletRequest("POST", "/api/v1/orders/42/confirm");
        when(timing.nanoTime()).thenReturn(123L, 456L);

        filter.doFilter(request, new MockHttpServletResponse(), (input, output) ->
            assertThat(input.getAttribute(PaymentRequestTimingFilter.STARTED_NANOS)).isEqualTo(123L));
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) ->
            assertThat(input.getAttribute(PaymentRequestTimingFilter.STARTED_NANOS)).isEqualTo(123L));
    }

    @Test
    void queryDoesNotStartAPaymentDeadline() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/orders/42");
        new PaymentRequestTimingFilter(timing).doFilter(request, new MockHttpServletResponse(), (input, output) ->
            assertThat(input.getAttribute(PaymentRequestTimingFilter.STARTED_NANOS)).isNull());
        verifyNoInteractions(timing);
    }
}
