package com.loopers.config.preservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

class PreservationProfileGuardTest {
    private static final AtomicBoolean BEAN_CREATED = new AtomicBoolean();

    @BeforeEach
    void resetObservation() {
        BEAN_CREATED.set(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    @DisplayName("보존 프로필과 local 또는 test를 혼용하면 bean 생성 전에 거절한다")
    void rejectsMixedProfilesBeforeBeanCreation(String profile) {
        var arguments = safeArguments("ranking-preserve," + profile);

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ranking-preserve");
        assertThat(BEAN_CREATED).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "spring.jpa.hibernate.ddl-auto,create",
        "spring.jpa.hibernate.ddl-auto,create-drop",
        "spring.jpa.hibernate.ddl-auto,update",
        "spring.jpa.hibernate.ddl-auto,none",
        "spring.sql.init.mode,always",
        "spring.sql.init.mode,embedded",
        "spring.batch.jdbc.initialize-schema,always",
        "spring.batch.jdbc.initialize-schema,embedded"
    })
    @DisplayName("명령행에서 보존 계약을 덮어쓰면 초기화 전에 해당 설정을 안내하며 거절한다")
    void rejectsUnsafeEffectiveSettings(String property, String value) {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--" + property + "="));
        arguments.add("--" + property + "=" + value);

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(property);
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("camelCase 이름으로 전달한 DDL 설정도 실제 Spring 바인딩 기준으로 거절한다")
    void rejectsRelaxedDdlSettingName() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--spring.jpa.hibernate.ddl-auto="));
        arguments.add("--spring.jpa.hibernate.ddlAuto=create");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.jpa.hibernate.ddl-auto");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("camelCase 이름으로 전달한 Batch 초기화 설정도 거절한다")
    void rejectsRelaxedBatchInitializationName() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--spring.batch.jdbc.initialize-schema="));
        arguments.add("--spring.batch.jdbc.initializeSchema=always");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.batch.jdbc.initialize-schema");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("camelCase 연결 설정으로 명시한 보존 DB 대상을 바꿀 수 없다")
    void rejectsRelaxedDatabaseUrlName() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--datasource.mysql-jpa.main.jdbc-url="));
        arguments.add("--datasource.mysql-jpa.main.jdbcUrl=jdbc:mysql://127.0.0.1:2/loopers");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMMERCE_MYSQL_JDBC_URL");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("앱 표시 이름을 바꿔도 Batch 초기화 보호를 우회할 수 없다")
    void rejectsBatchInitializationRegardlessOfApplicationName() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--spring.application.name=")
            || argument.startsWith("--spring.batch.jdbc.initialize-schema="));
        arguments.add("--spring.application.name=ranking-worker");
        arguments.add("--spring.batch.jdbc.initialize-schema=always");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.batch.jdbc.initialize-schema");
        assertThat(BEAN_CREATED).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "spring.jpa.properties.hibernate.hbm2ddl.auto,create",
        "spring.jpa.properties.hibernate.hbm2ddl.auto,none",
        "spring.jpa.properties[hibernate.hbm2ddl.auto],create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.database.action,drop-and-create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.database.action,none",
        "spring.jpa.properties.javax.persistence.schema-generation.database.action,create"
    })
    @DisplayName("Hibernate와 JPA의 직접 DDL 설정으로 보존 계약을 우회할 수 없다")
    void rejectsNativeSchemaGenerationOverrides(String property, String value) {
        var arguments = safeArguments("ranking-preserve");
        arguments.add("--" + property + "=" + value);

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.jpa.properties");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("다른 설정에 DB URL이 있어도 명시한 보존 대상 URL이 없으면 거절한다")
    void rejectsImplicitDatabaseTarget() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--COMMERCE_MYSQL_JDBC_URL="));

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMMERCE_MYSQL_JDBC_URL");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("더 높은 우선순위의 DB 설정이 명시한 보존 대상을 바꾸면 거절한다")
    void rejectsConflictingEffectiveDatabaseTarget() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--datasource.mysql-jpa.main.jdbc-url="));
        arguments.add("--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:2/loopers");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMMERCE_MYSQL_JDBC_URL");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("보존 실행의 DB URL이 비어 있으면 bean 생성 전에 거절한다")
    void rejectsMissingDatabaseUrl() {
        var arguments = safeArguments("ranking-preserve");
        arguments.removeIf(argument -> argument.startsWith("--datasource.mysql-jpa.main.jdbc-url="));
        arguments.add("--datasource.mysql-jpa.main.jdbc-url=");

        assertThatThrownBy(() -> startAndClose(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMMERCE_MYSQL_JDBC_URL");
        assertThat(BEAN_CREATED).isFalse();
    }

    @Test
    @DisplayName("보존 계약을 지키는 설정은 컨텍스트를 생성한다")
    void acceptsSafePreservationSettings() {
        startAndClose(safeArguments("ranking-preserve"));

        assertThat(BEAN_CREATED).isTrue();
    }

    @Test
    @DisplayName("기존 test 프로필의 create 설정에는 새 보존 가드를 적용하지 않는다")
    void leavesExistingProfilesUnchanged() {
        var arguments = safeArguments("test");
        arguments.removeIf(argument -> argument.startsWith("--spring.jpa.hibernate.ddl-auto="));
        arguments.add("--spring.jpa.hibernate.ddl-auto=create");

        startAndClose(arguments);

        assertThat(BEAN_CREATED).isTrue();
    }

    private List<String> safeArguments(String profiles) {
        return new ArrayList<>(List.of(
            "--spring.config.location=classpath:jpa.yml",
            "--spring.profiles.active=" + profiles,
            "--spring.application.name=commerce-batch",
            "--spring.main.banner-mode=off",
            "--logging.config=",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.sql.init.mode=never",
            "--spring.batch.jdbc.initialize-schema=never",
            "--COMMERCE_MYSQL_JDBC_URL=jdbc:mysql://127.0.0.1:1/loopers",
            "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:1/loopers"
        ));
    }

    private void startAndClose(List<String> arguments) {
        try (var ignored = new SpringApplicationBuilder(ObservationConfiguration.class)
            .web(WebApplicationType.NONE).run(arguments.toArray(String[]::new))) {
            assertThat(BEAN_CREATED).isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean
        Object initializationObservation() {
            BEAN_CREATED.set(true);
            return new Object();
        }
    }
}
