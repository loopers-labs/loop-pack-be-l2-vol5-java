package com.loopers.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * commerce-api 에만 적용하는 JSON 설정. 공용 모듈(supports:jackson)은 다른 앱에도 영향을 주므로 바꾸지 않는다.
 */
@Configuration
public class CommerceApiJacksonConfig {

    public static final String RESPONSE_TIME_ZONE = "Asia/Seoul";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer commerceApiJacksonCustomizer() {
        return builder -> builder
            // 정수 필드에 소수를 보내면 소수점을 버려 받지 않고 형식 오류(400)로 거절한다 (설계 D-37)
            .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            // 방금 만든 엔티티(시스템 시간대)와 DB 에서 읽은 엔티티(UTC)의 시각 표기가 갈리지 않도록 한 시간대로 내보낸다 (설계 D-40)
            .timeZone(TimeZone.getTimeZone(RESPONSE_TIME_ZONE));
    }
}
