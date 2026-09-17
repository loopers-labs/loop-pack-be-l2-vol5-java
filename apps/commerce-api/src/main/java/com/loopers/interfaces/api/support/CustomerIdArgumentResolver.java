package com.loopers.interfaces.api.support;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 모든 고객 요청에서 X-USER-ID 헤더의 존재·숫자 형식과 User 존재 여부를 확인한다.
 * 각 기능의 Service 는 같은 검증을 반복하지 않는다.
 */
@RequiredArgsConstructor
public class CustomerIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-USER-ID";

    private final UserService userService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CustomerId.class)
            && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        String header = webRequest.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new CoreException(ErrorType.INVALID_REQUEST, USER_ID_HEADER + " 헤더가 필요합니다.");
        }

        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.INVALID_REQUEST, USER_ID_HEADER + " 헤더는 숫자여야 합니다.");
        }

        userService.requireExists(userId);
        return userId;
    }
}
