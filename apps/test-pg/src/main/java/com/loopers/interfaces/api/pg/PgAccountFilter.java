package com.loopers.interfaces.api.pg;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 실습용 제공자 계정 검사이며 고객 인증 기능이 아니다. */
@Slf4j
@Component
public class PgAccountFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/api/v1/payments".equals(request.getServletPath())) {
            log.info("operation=PG_CREATE_RECEIVED");
        }
        String account = request.getHeader("X-USER-ID");
        if (!"135135".equals(account)) {
            response.setStatus(account == null ? 400 : 403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"INVALID_PROVIDER_ACCOUNT\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
