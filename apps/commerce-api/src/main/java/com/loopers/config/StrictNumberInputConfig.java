package com.loopers.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 요청 본문의 정수 필드에 실수(1.9)나 문자열 숫자("1000")가 오면 정수로 바꾸지 않고 읽기 실패(400)로 만든다.
 * 금액·재고·수량은 형식이 틀리면 값을 추측하지 않고 거절한다 (PRD-01, PNT-02, design.md 13-2).
 * 공용 supports/jackson 설정 뒤에 적용되며, commerce-api에만 영향을 준다.
 */
@Configuration
public class StrictNumberInputConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictIntegerInput() {
        return builder -> builder
            .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
