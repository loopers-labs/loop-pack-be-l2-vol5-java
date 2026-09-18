package com.loopers.interfaces.api.auth;

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
 * X-USER-ID 헤더를 읽어 존재까지 확인한다. 누락 · 형식 오류 · 없는 사용자는 모두 UNAUTHENTICATED 다 (설계 6.1).
 * 파라미터에 LoginUser 를 선언한 곳에서만 동작하므로, 요청자가 필요한 API 가 이 확인을 빠뜨릴 수 없다.
 */
@RequiredArgsConstructor
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-USER-ID";

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
        if (userId == null || !userFacade.exists(userId)) {
            throw new CoreException(ErrorType.UNAUTHENTICATED);
        }
        return new LoginUser(userId);
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
