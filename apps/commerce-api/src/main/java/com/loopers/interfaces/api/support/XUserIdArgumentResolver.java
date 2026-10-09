package com.loopers.interfaces.api.support;

import com.loopers.application.shopping.query.UserQueryDao;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
// X-USER-ID 헤더를 사용자 ID로 변환하는 리졸버
public class XUserIdArgumentResolver implements HandlerMethodArgumentResolver {
    static final String HEADER_NAME = "X-USER-ID";
    private static final String REQUIRED_MESSAGE = "X-USER-ID는 필수입니다.";
    private static final String INVALID_MESSAGE = "X-USER-ID는 양의 정수여야 합니다.";

    private final UserQueryDao userQueryDao;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> parameterType = parameter.getParameterType();
        return parameter.hasParameterAnnotation(XUserId.class)
            && (parameterType == long.class || parameterType == Long.class);
    }

    // 헤더 파싱 및 사용자 존재 검증
    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        String headerValue = webRequest.getHeader(HEADER_NAME);
        if (headerValue == null || headerValue.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, REQUIRED_MESSAGE);
        }

        long userId;
        try {
            userId = Long.parseLong(headerValue);
        } catch (NumberFormatException e) {
            throw invalidUserId();
        }
        if (userId <= 0) {
            throw invalidUserId();
        }
        userQueryDao.findById(userId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.USER_NOT_FOUND));
        return userId;
    }

    private CoreException invalidUserId() {
        return new CoreException(ErrorType.BAD_REQUEST, INVALID_MESSAGE);
    }
}
