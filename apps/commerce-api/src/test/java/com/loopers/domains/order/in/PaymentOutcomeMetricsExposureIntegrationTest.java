package com.loopers.domains.order.in;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

import static com.loopers.domains.order.in.PrometheusScrape.outcome;
import static com.loopers.domains.order.in.PrometheusScrape.scrape;
import static org.assertj.core.api.Assertions.assertThat;

// 기본 @SpringBootTest는 metrics export를 꺼 /actuator/prometheus가 404이므로 @AutoConfigureObservability가 필요하다.
// 종결 없이 띄운 직후에도 세 결과의 시계열이 있어야 한다. 값은 컨텍스트 공유 여부에 흔들리지 않게 보지 않는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class PaymentOutcomeMetricsExposureIntegrationTest {
    private static final List<String> RESULTS = List.of("confirmed", "failed", "expired");

    @LocalManagementPort private int managementPort;

    @Test
    @DisplayName("prometheus 응답에 결제 종결 결과 counter가 confirmed·failed·expired마다 한 줄씩 있다")
    void exposesOutcomeCounterForEachResult() throws Exception {
        String body = scrape(managementPort);

        for (String result : RESULTS) {
            Optional<Double> value = outcome(body, result);
            assertThat(value).as("result=%s 시계열", result).isPresent();
        }
    }
}
