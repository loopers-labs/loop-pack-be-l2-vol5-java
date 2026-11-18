package com.loopers.support.preservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

public final class PreservationContexts {
    private PreservationContexts() {}

    public static ConfigurableApplicationContext start(Class<?> configuration, String... extra) {
        return start(configuration, new StandardEnvironment(), extra);
    }

    public static ConfigurableApplicationContext start(Class<?> configuration, ConfigurableEnvironment environment,
                                                       String... extra) {
        var isolatedProperties = new LinkedHashMap<String, Object>();
        // Existing reusable fixtures publish JVM-global connection properties. Isolate this environment only.
        System.getProperties().forEach((key, value) -> {
            if (!key.toString().startsWith("datasource.mysql-jpa.main.")) {
                isolatedProperties.put(key.toString(), value);
            }
        });
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, isolatedProperties));
        var arguments = new ArrayList<>(List.of(
            "--spring.profiles.active=ranking-preserve",
            "--spring.main.banner-mode=off",
            "--spring.main.web-application-type=none",
            "--spring.batch.job.enabled=false"
        ));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(configuration).environment(environment).web(WebApplicationType.NONE)
            .run(arguments.toArray(String[]::new));
    }
}
