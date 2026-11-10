package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedOutbox;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "order-events.outbox.poll-interval=100"})
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderOutboxManualResumePortTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String TOPIC = "outbox-resume-port-" + UUID.randomUUID();

    @LocalServerPort private int serverPort;
    @Value("${local.management.port}") private int managementPort;
    @Autowired private OrderConfirmedOutbox outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
    }

    @AfterEach
    void clean() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void 기존_웹서버가_실행중이어도_별도_앱으로_재개하고_기존_relay가_발행한다() throws Exception {
        var event = OrderConfirmed.confirmed(100L, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new OrderConfirmed.Item(10L, 2)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.record(event);
            jdbc.update("update order_outbox set status = 'BLOCKED', attempt_count = 3,"
                    + " cycle_attempt_count = 3, next_attempt_at = null where event_id = ?", event.eventId());
        });
        assertThat(statusOf(event.eventId())).isEqualTo("BLOCKED");
        assertThat(serverPort).isPositive();
        assertThat(managementPort).isPositive();

        try (var recovery = new SpringApplicationBuilder(CommerceApiApplication.class).run(
                "--spring.profiles.active=local,test", "--server.port=" + serverPort,
                "--management.server.port=" + managementPort, "--spring.jpa.hibernate.ddl-auto=none",
                "--payment-provider.base-url=" + PG.baseUrl(), "--order-events.topic=" + TOPIC,
                "--order-events.outbox.resume-event-id=" + event.eventId())) {
            int recoveryPort = ((ServletWebServerApplicationContext) recovery).getWebServer().getPort();
            assertThat(recoveryPort).isPositive().isNotEqualTo(serverPort).isNotEqualTo(managementPort);
            assertThat(recovery.getEnvironment().getProperty("management.server.port")).isEqualTo("-1");
            assertThat(recovery.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(statusOf(event.eventId())).isEqualTo("PUBLISHED");
                assertThat(jdbc.queryForObject("select attempt_count from order_outbox where event_id = ?",
                        Integer.class, event.eventId())).isEqualTo(4);
            });
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", serverPort), 1000);
                assertThat(socket.isConnected()).isTrue();
            }
        }
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("select status from order_outbox where event_id = ?", String.class, eventId);
    }
}
