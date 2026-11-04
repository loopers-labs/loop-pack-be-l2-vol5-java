package com.loopers.infrastructure.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 없는 DB에서 요청 스레드와 커넥션이 무한정 묶이지 않도록 MySQL 드라이버 timeout을 둔다. */
@SpringBootTest
class DatabaseDriverTimeoutTest {
    @Autowired private HikariDataSource dataSource;

    @Test
    void mainPoolPassesConnectAndSocketTimeoutsToTheDriver() {
        assertThat(dataSource.getDataSourceProperties())
            .containsEntry("connectTimeout", "3000")
            .containsEntry("socketTimeout", "10000")
            .containsEntry("rewriteBatchedStatements", "true");
    }
}
