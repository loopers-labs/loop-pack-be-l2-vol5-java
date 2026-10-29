package com.loopers.config.jpa;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** streamer 에만 둔 드라이버 timeout 이 공용 jpa.yml 의 속성과 합쳐져 실제 커넥션 설정으로 들어가는지 (6주차 D-98) */
@SpringBootTest
class DataSourceSettingsTest {

    @Autowired
    private HikariDataSource dataSource;

    @DisplayName("드라이버는 socketTimeout 10s · connectTimeout 3s 를 받고, 공용 속성(rewriteBatchedStatements)도 그대로 받는다.")
    @Test
    void receivesDriverTimeouts() {
        Properties properties = dataSource.getDataSourceProperties();

        assertAll(
            () -> assertThat(properties.getProperty("socketTimeout")).isEqualTo("10000"),
            () -> assertThat(properties.getProperty("connectTimeout")).isEqualTo("3000"),
            () -> assertThat(properties.getProperty("rewriteBatchedStatements")).isEqualTo("true")
        );
    }
}
