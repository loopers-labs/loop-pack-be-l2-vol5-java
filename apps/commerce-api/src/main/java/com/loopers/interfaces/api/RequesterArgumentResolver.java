package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.application.UserUseCase;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 고객 API의 {@link Requester} 인자를 채운다.
 * 헤더가 없거나, 숫자가 아니거나, 없는 사용자이면 같은 오류로 거절한다.
 */
@Component
public class RequesterArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String USER_HEADER = "X-USER-ID";

    private final UserUseCase userUseCase;

    public RequesterArgumentResolver(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Requester.class.equals(parameter.getParameterType());
    }

    @Override
    public Requester resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        return new Requester(userUseCase.identify(parseUserId(webRequest.getHeader(USER_HEADER))));
    }

    private Long parseUserId(String header) {
        if (header == null) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException exception) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
    }
}
