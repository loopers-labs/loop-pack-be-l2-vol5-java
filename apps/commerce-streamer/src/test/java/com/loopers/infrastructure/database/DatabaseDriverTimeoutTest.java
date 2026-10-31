package com.loopers.infrastructure.database;

import com.loopers.config.jpa.JpaConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 없는 DB에서 consumer 스레드가 무한정 기다리지 않도록 MySQL 드라이버 timeout을 둔다.
 * 예외가 나야 오류 처리기의 재시도·중단이 동작한다.
 */
@SpringBootTest(classes = DatabaseDriverTimeoutTest.DatabaseOnly.class)
class DatabaseDriverTimeoutTest {
    @Autowired private HikariDataSource dataSource;

    @Test
    void mainPoolPassesConnectAndSocketTimeoutsToTheDriver() {
        assertThat(dataSource.getDataSourceProperties())
            .containsEntry("connectTimeout", "3000")
            .containsEntry("socketTimeout", "10000")
            .containsEntry("rewriteBatchedStatements", "true");
    }

    /**
     * 서버가 30초 동안 응답하지 않는다. 드라이버는 10초에 읽기를 포기하고, 연결 정리에서도 한 번 더 최대 10초를 기다린다.
     * 그래서 상한은 socketTimeout의 두 배(약 20초)이고, 서버가 끝날 때(30초)까지 기다리지 않는다.
     * Docker Desktop의 port 전달을 거치는 실제 대기 시간이라 결과가 흔들린 실행이 있어 평소 check에서는 뺀다.
     */
    @Test
    @Tag("experiment")
    void queryOnAPooledConnectionFailsWithinTwoSocketTimeoutsWhenTheServerDoesNotAnswer() throws Exception {
        long started = System.nanoTime();
        SQLException failure = null;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT SLEEP(30)");
        } catch (SQLException timedOut) {
            failure = timedOut;
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertThat((Throwable) failure).isInstanceOf(SQLException.class)
            .hasRootCauseInstanceOf(SocketTimeoutException.class);
        assertThat(waited).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(23));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @ComponentScan(basePackageClasses = JpaConfig.class)
    @Import(MySqlTestContainersConfig.class)
    static class DatabaseOnly {}
}
