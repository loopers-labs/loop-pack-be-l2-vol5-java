package com.loopers.infrastructure.monitoring.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.loopers.application.pay.query.PaymentMetricQueryDao;
import com.loopers.support.test.E2ETest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@E2ETest
@AutoConfigureObservability(tracing = false)
@TestPropertySource(properties = {"management.server.port=0", "payment.metrics.refresh-enabled=false"})
class PaymentUnknownMetricsHttpIntegrationTest {
    @LocalManagementPort private int managementPort;
    @Autowired private TestRestTemplate http;
    @Autowired private PaymentUnknownMetrics metrics;
    @MockitoBean private PaymentMetricQueryDao dao;
    @MockitoBean private Clock clock;

    @Test
    @DisplayName("실제 Prometheus 수집은 집계 결과만 읽고 실패 시 NaN과 마지막 성공 시각을 노출한다")
    void scrapesPublishedCountAndLastSuccessTime() {
        Instant first = Instant.parse("2026-11-27T01:00:00Z");
        Instant failed = first.plusSeconds(15);
        Instant recovered = failed.plusSeconds(15);
        given(clock.instant()).willReturn(first);
        given(dao.countOverdueUnknown(first)).willReturn(OptionalLong.of(0));
        given(dao.countOverdueUnknown(failed)).willThrow(new DataAccessResourceFailureException("test database unavailable"));
        given(dao.countOverdueUnknown(recovered)).willReturn(OptionalLong.of(3));

        assertScrape(Double.NaN, Double.NaN);
        verifyNoInteractions(dao);

        metrics.refresh();
        assertScrape(0, first.getEpochSecond());
        assertScrape(0, first.getEpochSecond());
        verify(dao).countOverdueUnknown(first);

        given(clock.instant()).willReturn(failed);
        metrics.refresh();
        assertScrape(Double.NaN, first.getEpochSecond());
        verify(dao).countOverdueUnknown(failed);

        given(clock.instant()).willReturn(recovered);
        metrics.refresh();
        assertScrape(3, recovered.getEpochSecond());
        verify(dao).countOverdueUnknown(recovered);
        verifyNoMoreInteractions(dao);
    }

    private void assertScrape(double count, double lastSuccess) {
        var response = http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertSample(body, "commerce_payment_unknown", count);
        assertSample(body, "commerce_payment_unknown_last_success_timestamp_seconds", lastSuccess);
    }

    private static void assertSample(String body, String name, double expected) {
        List<String> lines = body.lines()
            .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
            .toList();
        assertThat(lines).as("Prometheus 표본 %s", name).hasSize(1);
        String line = lines.getFirst();
        double actual = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        if (Double.isNaN(expected)) {
            assertThat(actual).as(name).isNaN();
        } else {
            assertThat(actual).as(name).isEqualTo(expected);
        }
    }
}
