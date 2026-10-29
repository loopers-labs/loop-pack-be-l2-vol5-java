package com.loopers.experiment;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 드라이버 timeout 을 끈(0 = 무한) 기준선. D-99 적용 전 설정과 같음.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ApiDatabaseOutage*'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@SpringBootTest(properties = {
    "datasource.mysql-jpa.main.data-source-properties.socketTimeout=0",
    "datasource.mysql-jpa.main.data-source-properties.connectTimeout=0"
})
class ApiDatabaseOutageWithoutTimeoutExperiment extends ApiDatabaseOutageExperimentBase {

    @Override
    protected String mode() {
        return "timeout-없음";
    }
}
