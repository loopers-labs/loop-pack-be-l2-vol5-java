package com.loopers.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.loopers.interfaces.api.RequesterArgumentResolver;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RequesterArgumentResolver requesterArgumentResolver;

    public WebConfig(RequesterArgumentResolver requesterArgumentResolver) {
        this.requesterArgumentResolver = requesterArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(requesterArgumentResolver);
    }

    /**
     * 정수 필드에 1.5 같은 실수가 오면 버림하지 않고 요청 형식 오류로 거절한다.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejectFloatForInteger() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
