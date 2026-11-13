package com.loopers.user.adapter.in.web;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * X-USER-ID의 형식만 확인한다. DB에 묻지 않는다 ({@link TrustedUser}).
 */
@Component
public class TrustedUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TrustedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public TrustedUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(LoginUserArgumentResolver.USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
        try {
            long id = Long.parseLong(header.strip());
            if (id < 1) {
                throw new CoreException(ErrorType.UNAUTHORIZED);
            }
            return new TrustedUser(id);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
    }
}
