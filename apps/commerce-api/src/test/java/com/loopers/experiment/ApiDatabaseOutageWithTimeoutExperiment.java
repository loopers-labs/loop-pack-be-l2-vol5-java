package com.loopers.experiment;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 api 설정(socketTimeout 10s · connectTimeout 3s, D-99) 그대로의 DB 장애 실험.
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ApiDatabaseOutage*'
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@SpringBootTest
class ApiDatabaseOutageWithTimeoutExperiment extends ApiDatabaseOutageExperimentBase {

    @Override
    protected String mode() {
        return "timeout-있음";
    }
}
