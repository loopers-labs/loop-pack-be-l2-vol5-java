package com.loopers.interfaces.api.auth;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class RequesterIdArgumentResolver implements HandlerMethodArgumentResolver {
    public static final String HEADER = "X-USER-ID";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(RequesterId.class) && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        return parse(webRequest.getHeader(HEADER));
    }

    /** 헤더 값 → 사용자 ID. 누락·형식 오류는 ER-01 (DR-01: 셋을 나누지 않는다). */
    public static Long parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new CoreException(ErrorType.USER_NOT_FOUND, "요청자를 식별할 수 없습니다. [" + HEADER + " 누락]");
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.USER_NOT_FOUND, "요청자를 식별할 수 없습니다. [" + HEADER + " = " + headerValue + "]");
        }
    }
}
