package com.loopers.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterIdArgumentResolver;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * /api-admin/** 경계. X-USER-ID 헤더의 사용자를 조회해 관리자면 ROLE_ADMIN 인증을 세운다 (CON-03, ASM-26).
 * 실패는 ApiResponse 봉투로 ER-01(USER_NOT_FOUND, 404) / ER-02(NOT_ADMIN, 403) 를 쓴다.
 * Facade 도 설계 5-6 대로 UserService.getAdmin 을 다시 부른다. 이 필터는 HTTP 경계일 뿐 규칙의 주인이 아니다.
 */
@RequiredArgsConstructor
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            Long userId = RequesterIdArgumentResolver.parse(request.getHeader(RequesterIdArgumentResolver.HEADER));
            UserModel admin = userService.getAdmin(userId);
            var authentication = new UsernamePasswordAuthenticationToken(
                admin.getId(), null, List.of(new SimpleGrantedAuthority(ROLE_ADMIN)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (CoreException e) {
            writeFailure(response, e.getErrorType(), e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    public void writeFailure(HttpServletResponse response, ErrorType errorType, String message) throws IOException {
        response.setStatus(errorType.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorType.getCode(), message));
    }
}
