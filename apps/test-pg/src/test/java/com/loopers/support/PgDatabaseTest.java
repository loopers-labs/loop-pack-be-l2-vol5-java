package com.loopers.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/** JVM별 PG 전용 DB. commerce fixture의 전역 시스템 프로퍼티는 사용하지 않는다. */
public abstract class PgDatabaseTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loopers_test_pg");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        registry.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
    }
}
