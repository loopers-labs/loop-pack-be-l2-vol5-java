package com.loopers.interfaces.api.paging;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 쿼리 파라미터 page·size 를 {@link PageQuery} 로 받는다 (DR-19).
 * 누락은 기본값, 형식·범위 오류는 ER-08 INVALID_PAGE.
 */
@Component
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {
    public static final String PAGE = "page";
    public static final String SIZE = "size";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        return PageQuery.of(parse(PAGE, webRequest.getParameter(PAGE)), parse(SIZE, webRequest.getParameter(SIZE)));
    }

    /** 파라미터 값 → 정수. 누락·공백은 null(기본값), 형식 오류는 ER-08. */
    public static Integer parse(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.INVALID_PAGE, name + " 는 정수여야 합니다. [" + name + " = " + value + "]");
        }
    }
}
