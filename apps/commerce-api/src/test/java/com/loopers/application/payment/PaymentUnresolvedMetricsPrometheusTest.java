package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Actuator 응답에 업무 지표가 나오는지 확인한다 (10주차).
 * management port 는 monitoring.yml 에서 8081 고정이고 test 오버라이드가 없으므로, 0 을 주고 실제 주입된 포트를 쓴다.
 * 지표 집계 스케줄은 test profile 에서 꺼져 있으므로 테스트가 refresh() 를 직접 호출한다.
 * <p>
 * @AutoConfigureObservability 가 필요한 이유: Spring Boot 는 테스트에서 metrics export 를 기본으로 끈다
 * (management.defaults.metrics.export.enabled=false, simple=true 를 주입한다). 그래서 이 애너테이션이 없으면
 * MeterRegistry 가 SimpleMeterRegistry 가 되고 /actuator/prometheus 는 404 가 된다. 운영 설정 문제가 아니다.
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class PaymentUnresolvedMetricsPrometheusTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentUnresolvedMetricsPrometheusTest.class);
    private static final String CARD_TYPE = "TEST";

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private PaymentUnresolvedMetrics paymentUnresolvedMetrics;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TransactionTemplate transactionTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("health 는 UP 인데 미확인 결제가 있다는 사실이 Prometheus 응답에 그대로 드러난다.")
    void exposesUnresolvedMetrics_whileHealthIsUp() {
        PaymentAttempt unknown = paymentAttemptRepository.save(new PaymentAttempt(
            1L, 1L, "attempt-key-1", PaymentAttempt.providerOrderIdOf(1L), 1_000L, CARD_TYPE));
        paymentAttemptRepository.save(new PaymentAttempt(
            2L, 2L, "attempt-key-2", PaymentAttempt.providerOrderIdOf(2L), 2_000L, CARD_TYPE));
        transactionTemplate.executeWithoutResult(status ->
            paymentAttemptRepository.markUnknownIfRequested(unknown.getId()));

        paymentUnresolvedMetrics.refresh();

        ResponseEntity<String> health = get("/actuator/health");
        log.info("health 원문: status={} body={}", health.getStatusCode(), health.getBody());
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> lines = Arrays.stream(prometheus.getBody().split("\n"))
            .filter(line -> line.startsWith("commerce_payment_unresolved"))
            .toList();
        log.info("실제 Prometheus 응답 (commerce_payment_unresolved):\n{}", String.join("\n", lines));

        assertThat(lines).anyMatch(line -> line.startsWith("commerce_payment_unresolved{") && line.contains("state=\"unknown\"") && line.endsWith("1.0"));
        assertThat(lines).anyMatch(line -> line.startsWith("commerce_payment_unresolved{") && line.contains("state=\"requested\"") && line.endsWith("1.0"));
        assertThat(lines).anyMatch(line -> line.startsWith("commerce_payment_unresolved{") && line.contains("state=\"pending\"") && line.endsWith("0.0"));
        assertThat(lines).anyMatch(line -> line.contains("commerce_payment_unresolved_oldest_created_timestamp_seconds{") && line.contains("state=\"unknown\""));
        assertThat(lines).anyMatch(line -> line.startsWith("commerce_payment_unresolved_refresh_last_success_timestamp_seconds"));
        assertThat(lines).anyMatch(line -> line.contains("commerce_payment_unresolved_refresh_total{") && line.contains("result=\"success\""));
    }

    private ResponseEntity<String> get(String path) {
        return testRestTemplate.getForEntity("http://localhost:" + managementPort + path, String.class);
    }
}
