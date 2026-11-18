package com.loopers.support.preservation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

/** A private database whose lifetime is independent of the application contexts under test. */
public final class PreservedMySql implements AutoCloseable {
    private final MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("loopers").withUsername("application").withPassword("application");

    public PreservedMySql() {
        container.start();
    }

    public void prepareCommerceSchema() throws SQLException {
        try (var connection = DriverManager.getConnection(container.getJdbcUrl(), "root", container.getPassword())) {
            for (String script : List.of("000-commerce-baseline.sql", "003-payment-attempts.sql",
                "004-order-payment-method.sql", "005-payment-recovery-tasks.sql", "006-stock-adjustments.sql",
                "007-order-pg-status.sql", "008-order-confirmation-event-id.sql", "009-purchase-activity.sql",
                "010-order-event-failure.sql", "011-order-event-outbox.sql", "012-coupon-campaigns-slots.sql",
                "013-coupons.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/commerce/" + script));
            }
        }
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), container.getUsername(), container.getPassword());
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String[] arguments(String... extra) {
        var arguments = new ArrayList<>(List.of(
            "--COMMERCE_MYSQL_JDBC_URL=" + jdbcUrl(),
            "--datasource.mysql-jpa.main.jdbc-url=" + jdbcUrl(),
            "--datasource.mysql-jpa.main.username=" + container.getUsername(),
            "--datasource.mysql-jpa.main.password=" + container.getPassword(),
            "--datasource.mysql-jpa.main.maximum-pool-size=3",
            "--datasource.mysql-jpa.main.minimum-idle=0"
        ));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    @Override
    public void close() {
        container.stop();
    }
}
