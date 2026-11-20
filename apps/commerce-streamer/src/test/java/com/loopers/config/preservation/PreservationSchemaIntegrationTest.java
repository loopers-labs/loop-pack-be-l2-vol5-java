package com.loopers.config.preservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;

class PreservationSchemaIntegrationTest {
    @Test
    @DisplayName("명시 commerce SQL을 실제 앱의 Entity와 Repository 매핑으로 검증한다")
    void validatesExplicitSchemaWithActualMappings() throws Exception {
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            try (var context = PreservationContexts.start(JpaConfiguration.class, database.arguments())) {
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("ranking-preserve");
                assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
                assertThat(context.getEnvironment().getProperty("datasource.mysql-jpa.main.jdbc-url"))
                    .isEqualTo(database.jdbcUrl());
                var metamodel = context.getBean(EntityManagerFactory.class).getMetamodel();
                assertThat(metamodel.getEntities()).extracting(EntityType::getName).contains("OrderConfirmationInboxJpaEntity", "ProductPurchaseActivityJpaEntity");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan("com.loopers.config.jpa")
    static class JpaConfiguration {}
}
