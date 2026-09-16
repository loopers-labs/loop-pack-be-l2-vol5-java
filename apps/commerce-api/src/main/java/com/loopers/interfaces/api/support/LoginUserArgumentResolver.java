package com.loopers.interfaces.api.support;

import com.loopers.application.user.UserFacade;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 요청자 식별을 판정하는 한 곳 (USR-01, ADR-07).
 * X-USER-ID 헤더를 문자열로 읽어 누락·형식·존재를 확인하고, 하나라도 실패하면 UNAUTHORIZED를 던진다.
 */
@RequiredArgsConstructor
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String USER_ID_HEADER = "X-USER-ID";

    private final UserFacade userFacade;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return LoginUser.class.equals(parameter.getParameterType());
    }

    @Override
    public LoginUser resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        Long userId = parseUserId(webRequest.getHeader(USER_ID_HEADER));
        userFacade.checkExists(userId);
        return new LoginUser(userId);
    }

    private Long parseUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
        try {
            return Long.valueOf(headerValue);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
    }
}
