package com.loopers.config.experiment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Converter serialization/write and remaining MVC completion, not client receive time. */
@Configuration(proxyBeanMethods = false)
@ControllerAdvice
@Profile("experiment")
public class ExperimentResponseTiming implements ResponseBodyAdvice<Object>, WebMvcConfigurer {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ExperimentTrace.current() != null;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType mediaType,
        Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
        ServerHttpResponse response) {
        ExperimentTrace trace = ExperimentTrace.current();
        if (trace != null && trace.responseScope == null) {
            trace.responseScope = trace.scope("response.writeAndMvcCompletion");
        }
        return body;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                Exception exception) {
                ExperimentTrace trace = ExperimentTrace.current();
                if (trace != null) trace.finishResponse();
            }
        });
    }
}
