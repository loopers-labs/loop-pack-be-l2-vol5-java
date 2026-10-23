package com.loopers.config.pg;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PgJacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer pgStrictRequestJson() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var integerCoercion = mapper.coercionConfigFor(LogicalType.Integer);
            integerCoercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            integerCoercion.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            integerCoercion.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        });
    }
}
