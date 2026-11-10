package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.loopers.application.order.OrderConfirmedOutbox;
import com.loopers.application.order.OutboxKafkaSender;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.infrastructure.order.OrderOutboxRelayConfiguration;
import com.loopers.infrastructure.order.OrderOutboxRepository;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "order-events.outbox.poll-interval=100")
@ActiveProfiles({"local", "test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedOutboxSchedulerTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String TOPIC = "order-events-outbox-scheduler-" + UUID.randomUUID();

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture fixture;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private OrderConfirmedOutbox outbox;
    @Autowired private OrderOutboxRepository repository;
    @Autowired private OutboxKafkaSender sender;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
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
    void 로컬_설정으로_활성화된_scheduler가_저장된_이벤트를_발행한다() {
        var order = fixture.createUnpaidOrder();
        var accepted = requestPayment.request(order.userId(), order.orderId(), fixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(order.userId(), order.orderId());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from order_outbox where order_id = ?",
                        String.class, order.orderId())).isEqualTo("PUBLISHED"));
    }

    @Test
    void blocked를_수동_재개하면_scheduler가_발행한다() {
        var event = OrderConfirmed.confirmed(100L, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new OrderConfirmed.Item(10L, 2)));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            outbox.record(event);
            jdbc.update("update order_outbox set status = 'BLOCKED', attempt_count = 3,"
                    + " cycle_attempt_count = 3, next_attempt_at = null where event_id = ?", event.eventId());
        });
        assertThat(jdbc.queryForObject("select status from order_outbox where event_id = ?",
                String.class, event.eventId())).isEqualTo("BLOCKED");

        new ApplicationContextRunner()
                .withUserConfiguration(OrderOutboxRelayConfiguration.class)
                .withBean(OrderOutboxRepository.class, () -> repository)
                .withBean(OutboxKafkaSender.class, () -> sender)
                .withPropertyValues("spring.profiles.active=local", "order-events.outbox.enabled=true",
                        "order-events.outbox.resume-event-id=" + event.eventId())
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    var resume = context.getBean("resumeOrderOutbox", ApplicationRunner.class);
                    assertThatCode(() -> resume.run(new DefaultApplicationArguments(new String[0])))
                            .doesNotThrowAnyException();

                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                        assertThat(jdbc.queryForObject("select status from order_outbox where event_id = ?",
                                String.class, event.eventId())).isEqualTo("PUBLISHED");
                        assertThat(jdbc.queryForObject("select attempt_count from order_outbox where event_id = ?",
                                Integer.class, event.eventId())).isEqualTo(4);
                    });
                });
    }
}
