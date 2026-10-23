package com.loopers.interfaces.api.order;

import com.loopers.application.order.PaymentConfirmationTiming;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Starts the single confirmation budget before MVC body conversion and database work. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentRequestTimingFilter extends OncePerRequestFilter {
    static final String STARTED_NANOS = PaymentRequestTimingFilter.class.getName() + ".startedNanos";
    private final PaymentConfirmationTiming timing;

    public PaymentRequestTimingFilter(PaymentConfirmationTiming timing) {
        this.timing = timing;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !path.matches("/api/v1/orders/[^/]+/confirm");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getAttribute(STARTED_NANOS) == null) {
            request.setAttribute(STARTED_NANOS, timing.nanoTime());
        }
        chain.doFilter(request, response);
    }
}
