package com.loopers.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.ReconcileOrderPaymentFacade;
import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=${GAUGE_DEMO_PORT:0}")
class UnknownPaymentGaugeIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(UnknownPaymentGaugeIntegrationTest.class);
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String METRIC = "commerce_payment_unknown";

    @LocalManagementPort private int managementPort;
    // A live Prometheus scraper needs time to observe each phase. Regular tests advance immediately.
    @Value("${GAUGE_DEMO_HOLD:0s}") private Duration observationWindow;
    @Autowired private ObjectMapper mapper;
    @Autowired private TestRestTemplate http;
    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private OrderPaymentFixture fixture;
    @Autowired private DatabaseCleanUp cleanUp;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void 프로메테우스에서_UNKNOWN_결제만_집계하고_대사하면_0으로_돌아간다() throws IOException, InterruptedException {
        Path reports = Files.createDirectories(Path.of("build", "reports", "unknown-payment-gauge"));
        Path run = Files.createTempDirectory(reports, "run-");
        var pendingOrder = fixture.createUnpaidOrder();
        var pending = requestPayment.request(pendingOrder.userId(), pendingOrder.orderId(),
                fixture.createPaymentRequestCommand());
        assertThat(pending.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertScrape(run, "baseline", 0);

        PG.loseResponseAfterCreatingTransaction();
        var affectedOrders = new ArrayList<OrderPaymentFixture.PreparedOrder>();
        for (String attemptKey : List.of("017f22e2-79b0-7cc3-98c4-dc0c0c073990",
                "017f22e2-79b0-7cc3-98c4-dc0c0c073991", "017f22e2-79b0-7cc3-98c4-dc0c0c073992")) {
            var order = fixture.createUnpaidOrder();
            var command = new RequestOrderPaymentFacade.Command(UUID.fromString(attemptKey), "TEST", "0000-0000-0000-0000");
            var result = requestPayment.request(order.userId(), order.orderId(), command);
            assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            PG.succeedPaymentForOrder(result.providerOrderId());
            affectedOrders.add(order);
        }
        verifyOrderDetails(run, "fault", affectedOrders, OrderStatus.PAYMENT_PENDING, PaymentStatus.UNKNOWN);
        assertScrape(run, "fault", 3);

        for (var order : affectedOrders) {
            var result = reconcilePayment.reconcile(order.userId(), order.orderId());
            assertThat(result.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
        }
        verifyOrderDetails(run, "recovery", affectedOrders, OrderStatus.CONFIRMED, PaymentStatus.SUCCESS);
        assertScrape(run, "recovery", 0);
    }

    private void verifyOrderDetails(Path run, String phase, List<OrderPaymentFixture.PreparedOrder> orders,
                                    OrderStatus orderStatus, PaymentStatus paymentStatus) throws IOException {
        for (var order : orders) {
            var headers = new HttpHeaders();
            headers.set("X-USER-ID", Long.toString(order.userId()));
            var response = http.exchange("/api/v1/orders/{id}", HttpMethod.GET, new HttpEntity<>(headers),
                    String.class, order.orderId());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = response.getBody();
            assertThat(body).isNotNull();
            Files.writeString(run.resolve(phase + "-order-" + order.orderId() + ".json"), body);
            var data = mapper.readTree(body).required("data");
            assertThat(data.required("orderId").asLong()).isEqualTo(order.orderId());
            assertThat(data.required("status").asText()).isEqualTo(orderStatus.name());
            assertThat(data.required("payment").required("status").asText()).isEqualTo(paymentStatus.name());
        }
    }

    private void assertScrape(Path run, String phase, int expected) throws IOException, InterruptedException {
        var response = http.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        Files.writeString(run.resolve(phase + ".prom"), body);
        assertThat(body.lines()).contains("# TYPE " + METRIC + " gauge");
        var samples = body.lines()
                .filter(line -> line.startsWith(METRIC + "{") || line.startsWith(METRIC + " "))
                .toList();
        assertThat(samples).hasSize(1);
        String sample = samples.getFirst();
        log.info("{}: {}", phase, sample);
        assertThat(Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1))).isEqualTo(expected);
        Thread.sleep(observationWindow);
    }
}
