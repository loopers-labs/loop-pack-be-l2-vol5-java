package com.loopers.config.preservation;

import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

public final class PreservationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("ranking-preserve"))) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("ranking-preserve는 local/test와 함께 사용할 수 없습니다.");
        }
        requireSetting(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        requireSetting(environment, "spring.sql.init.mode", "never");
        if (environment.containsProperty("spring.batch.jdbc.initialize-schema")) {
            requireSetting(environment, "spring.batch.jdbc.initialize-schema", "never");
        }
        requireNativeSchemaSettings(environment);
        String requestedUrl;
        String jdbcUrl;
        try {
            requestedUrl = environment.getProperty("COMMERCE_MYSQL_JDBC_URL");
            jdbcUrl = environment.getProperty("datasource.mysql-jpa.main.jdbc-url");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("ranking-preserve에는 COMMERCE_MYSQL_JDBC_URL 명시가 필요합니다.", exception);
        }
        if (!StringUtils.hasText(requestedUrl) || !StringUtils.hasText(jdbcUrl)) {
            throw new IllegalStateException("ranking-preserve에는 COMMERCE_MYSQL_JDBC_URL 명시가 필요합니다.");
        }
        if (!requestedUrl.equals(jdbcUrl)) {
            throw new IllegalStateException("ranking-preserve의 datasource.mysql-jpa.main.jdbc-url은 "
                + "명시한 COMMERCE_MYSQL_JDBC_URL과 같아야 합니다.");
        }
    }

    private void requireNativeSchemaSettings(ConfigurableEnvironment environment) {
        var properties = Binder.get(environment)
            .bind("spring.jpa.properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        String nativeDdl = properties.get("hibernate.hbm2ddl.auto");
        if (nativeDdl != null && !"validate".equals(nativeDdl)) {
            throw new IllegalStateException("ranking-preserve의 spring.jpa.properties.hibernate.hbm2ddl.auto는 "
                + "validate만 사용할 수 있습니다.");
        }
        for (String action : List.of("jakarta.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.database.action")) {
            if (properties.containsKey(action)) {
                throw new IllegalStateException("ranking-preserve에서는 spring.jpa.properties." + action
                    + " 대신 spring.jpa.hibernate.ddl-auto=validate를 사용해야 합니다.");
            }
        }
    }

    private void requireSetting(ConfigurableEnvironment environment, String property, String expected) {
        if (!expected.equals(environment.getProperty(property))) {
            throw new IllegalStateException("ranking-preserve는 " + property + "=" + expected + " 설정이 필요합니다.");
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
