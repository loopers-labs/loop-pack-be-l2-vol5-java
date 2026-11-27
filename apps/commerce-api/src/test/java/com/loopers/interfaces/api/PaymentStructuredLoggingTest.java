package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.interfaces.api.payment.PaymentDto;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class PaymentStructuredLoggingTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String TRACE_ID = "68dd1eb7214d4e9287d61bba8ef9b150";
    private static final UUID ORIGINAL = UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f");
    private static final UUID CONFLICTING = UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c073990");

    @Autowired private OrderPaymentFixture fixture;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private ObjectMapper mapper;
    @Autowired private Environment environment;
    @Autowired private CircuitBreaker lookupCircuit;
    @LocalServerPort private int port;
    private final TestRestTemplate client = new TestRestTemplate();

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry properties) {
        properties.add("payment-provider.base-url", PG::baseUrl);
    }

    @BeforeEach
    void resetPg() {
        PG.reset();
        lookupCircuit.reset();
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
    void 결제_판단과_PG_조회_회차를_실제_HTTP_trace와_시도키로_찾는다() throws Exception {
        var order = fixture.createUnpaidOrder();
        String output = captureApplicationLogs(() -> {
            var first = post(order, "/confirm", ORIGINAL);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(post(order, "/confirm", ORIGINAL).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(post(order, "/confirm", CONFLICTING).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            PG.succeedPaymentForOrder(first.getBody().requiredAt("/data/providerOrderId").asText());
            PG.respondToLookupRequestsWith(503, 200);
            assertThat(post(order, "/payment/reconcile", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(post(order, "/payment/reconcile", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(PG.createRequestCount()).isEqualTo(1);
            assertThat(PG.lookupRequestCount()).isEqualTo(3);
        });

        var evidence = new ArrayList<String>();
        var entries = new ArrayList<JsonNode>();
        for (String line : output.lines().toList()) {
            var event = mapper.readTree(line);
            if (TRACE_ID.equals(event.path("traceId").asText())) {
                assertThat(event.required("level").asText()).isNotIn("WARN", "ERROR");
            }
            if (Set.of("payment_request", "pg_lookup_attempt").contains(event.path("step").asText())) {
                evidence.add(line);
                entries.add(event);
            }
        }
        assertThat(entries).hasSize(6).allSatisfy(event -> {
            assertThat(event.required("level").textValue()).isEqualTo("INFO");
            assertThat(event.required("traceId").textValue()).isEqualTo(TRACE_ID);
            assertThat(event.required("spanId").textValue()).matches("[a-f0-9]{16}");
            assertThat(event.required("orderId").textValue()).isEqualTo(Long.toString(order.orderId()));
            assertThat(event.toString()).doesNotContain("cardNo", "0000-0000-0000-0000", "X-USER-ID", "Authorization");
        });
        var decisions = entries.stream().filter(e -> "payment_request".equals(e.path("step").asText())).toList();
        assertThat(decisions).extracting(e -> e.required("decision").textValue())
                .containsExactly("send_to_pg", "reuse_existing", "attempt_key_conflict");
        assertThat(decisions).extracting(e -> e.required("paymentAttemptKey").textValue())
                .containsExactly(ORIGINAL.toString(), ORIGINAL.toString(), CONFLICTING.toString());

        var attempts = entries.stream().filter(e -> "pg_lookup_attempt".equals(e.path("step").asText())).toList();
        assertThat(attempts).allSatisfy(e -> {
            assertThat(e.required("paymentAttemptKey").textValue()).isEqualTo(ORIGINAL.toString());
            assertThat(e.required("pgLookupAttempt").isIntegralNumber()).isTrue();
        });
        assertThat(attempts).extracting(e -> e.required("pgLookupAttempt").intValue()).containsExactly(1, 2, 1);
        assertThat(attempts).extracting(e -> e.required("outcome").textValue()).containsExactly("failed", "success", "success");
        assertThat(attempts.getFirst().required("reason").textValue()).isEqualTo("http_error");
        var pgStatus = attempts.getFirst().required("pgHttpStatus");
        assertThat(pgStatus.isIntegralNumber()).isTrue();
        assertThat(pgStatus.intValue()).isEqualTo(503);
        assertThat(attempts.get(0).get("spanId")).isEqualTo(attempts.get(1).get("spanId"));
        assertThat(entries.stream().map(e -> e.required("spanId").textValue()).distinct()).hasSize(5);

        var reports = Files.createDirectories(Path.of("build/reports/payment-logs"));
        Files.write(Files.createTempDirectory(reports, "run-").resolve("payment-requests.jsonl"), evidence);
    }

    @Test
    void 대사와_콜백에서_PG_재시도를_소진하면_요청별로_최종_경고_하나를_남긴다() throws Exception {
        var order = fixture.createUnpaidOrder();
        var payment = post(order, "/confirm", ORIGINAL).getBody().required("data");
        var providerOrderId = payment.required("providerOrderId").asText();
        var callback = new PaymentDto.CallbackRequest(providerOrderId, payment.required("transactionKey").asText(), PaymentStatus.SUCCESS);
        PG.failPaymentLookups();

        String output = captureApplicationLogs(() -> {
            assertThat(post(order, "/payment/reconcile", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            var response = client.postForEntity("http://127.0.0.1:" + port + "/api/v1/payments/callback",
                    new HttpEntity<>(callback, traceHeaders()), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        });
        assertThat(PG.lookupRequestCount()).isEqualTo(4);

        var warnings = new ArrayList<JsonNode>();
        var attempts = new ArrayList<JsonNode>();
        for (String line : output.lines().toList()) {
            var event = mapper.readTree(line);
            if (!TRACE_ID.equals(event.path("traceId").asText())) {
                continue;
            }
            if ("pg_lookup_attempt".equals(event.path("step").asText())) {
                attempts.add(event);
            }
            if (Set.of("WARN", "ERROR").contains(event.path("level").asText())) {
                warnings.add(event);
            }
        }
        assertThat(warnings).extracting(e -> e.required("lookupBy").asText()).containsExactly("order_id", "transaction_key");
        assertThat(warnings).allSatisfy(warning -> {
            assertThat(warning.required("level").asText()).isEqualTo("WARN");
            assertThat(warning.required("step").asText()).isEqualTo("pg_lookup");
            assertThat(warning.required("outcome").asText()).isEqualTo("unavailable");
            assertThat(warning.required("orderId").asText()).isEqualTo(Long.toString(order.orderId()));
            assertThat(warning.required("providerOrderId").asText()).isEqualTo(providerOrderId);
            assertThat(warning.required("paymentAttemptKey").asText()).isEqualTo(ORIGINAL.toString());
            assertThat(warning.required("reason").asText()).isEqualTo("http_error");
            assertThat(warning.required("pgHttpStatus").intValue()).isEqualTo(503);
            var span = warning.required("spanId");
            assertThat(attempts.stream().filter(e -> e.required("spanId").equals(span)))
                    .extracting(e -> e.required("pgLookupAttempt").intValue()).containsExactly(1, 2);
        });
        assertThat(attempts).hasSize(4).allSatisfy(e -> assertThat(e.required("level").asText()).isEqualTo("INFO"));
    }

    private String captureApplicationLogs(Runnable requests) throws Exception {
        var output = new ByteArrayOutputStream();
        var context = new LoggerContext();
        var application = (Logger) LoggerFactory.getLogger("com.loopers");
        try {
            context.putObject(Environment.class.getName(), environment);
            var configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(new ByteArrayInputStream("""
                    <configuration>
                        <include resource="appenders/json-console-appender.xml"/>
                        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
                    </configuration>
                    """.getBytes(StandardCharsets.UTF_8)));
            var console = (OutputStreamAppender<ILoggingEvent>) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
            assertThat(console.isStarted()).isTrue();
            console.setOutputStream(output);
            application.addAppender(console);
            try {
                requests.run();
            } finally {
                application.detachAppender(console);
            }
        } finally {
            context.stop();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private ResponseEntity<JsonNode> post(OrderPaymentFixture.PreparedOrder order, String operation, UUID attemptKey) {
        var headers = traceHeaders();
        headers.set("X-USER-ID", Long.toString(order.userId()));
        var body = attemptKey == null ? null : new PaymentDto.ConfirmRequest(attemptKey, "TEST", "0000-0000-0000-0000");
        return client.postForEntity("http://127.0.0.1:" + port + "/api/v1/orders/{orderId}" + operation,
                new HttpEntity<>(body, headers), JsonNode.class, order.orderId());
    }

    private HttpHeaders traceHeaders() {
        var headers = new HttpHeaders();
        headers.set("traceparent", "00-" + TRACE_ID + "-f290aa38cb457f2e-01");
        return headers;
    }
}
