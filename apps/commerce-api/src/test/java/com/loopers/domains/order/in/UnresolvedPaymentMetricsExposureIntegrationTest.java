package com.loopers.domains.order.in;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

import static com.loopers.domains.order.in.PrometheusScrape.scrape;
import static com.loopers.domains.order.in.PrometheusScrape.unresolved;
import static org.assertj.core.api.Assertions.assertThat;

// test 프로필 기본값(갱신 스케줄러 꺼짐)으로 띄워 갱신 없이도 네 시계열이 있고 값이 NaN인지 본다.
// 기본 @SpringBootTest는 metrics export를 꺼 /actuator/prometheus가 404이므로 @AutoConfigureObservability가 필요하다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class UnresolvedPaymentMetricsExposureIntegrationTest {
    private static final List<String> STATUSES = List.of("send_started", "pending", "unknown", "needs_review");

    @LocalManagementPort private int managementPort;

    @Test
    @DisplayName("갱신 전에도 prometheus 응답에 미확인 결제 gauge가 status 네 값마다 한 줄씩 있고 값은 NaN이다")
    void exposesFourSeriesAsNaNBeforeRefresh() throws Exception {
        String body = scrape(managementPort);

        for (String status : STATUSES) {
            Optional<Double> value = unresolved(body, status);
            assertThat(value).as("status=%s 시계열", status).isPresent();
            assertThat(value.get()).as("status=%s 값", status).isNaN();
        }
    }
}
