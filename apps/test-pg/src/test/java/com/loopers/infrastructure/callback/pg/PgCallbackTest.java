package com.loopers.infrastructure.callback.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.pg.model.PgScenario;
import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import com.loopers.support.PgDatabaseTest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "pg.processing.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PgCallbackTest extends PgDatabaseTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ConcurrentHashMap<String, JsonNode> CALLBACKS = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    static final java.util.concurrent.ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer SERVER = server();

    @Autowired
    PgTransactionRepository repository;
    @Autowired
    PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void callback(DynamicPropertyRegistry registry) {
        registry.add("pg.callback-url", PgCallbackTest::callbackUrl);
    }

    @AfterAll
    static void closeReceiver() {
        SERVER.stop(0);
        HTTP_EXECUTOR.close();
    }

    @Test
    @DisplayName("callback을 지연하는 동안 다른 거래는 완료되고 정확한 알림을 한 번 보낸다")
    void delayedCallbackDoesNotBlockCompletion() {
        PgScenario delayed = PgScenario.resolve(PgScenario.Preset.NORMAL_SUCCESS,
                new PgScenario.Overrides(null, null, null, null, null, null, null, null, 3000L));
        PgTransaction first = insert("CALLBACK-DELAY-", delayed);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(load(first).status()).isEqualTo(PgStatus.SUCCESS));
        PgTransaction second = insert("CALLBACK-NORMAL-", PgScenario.resolve(null, null));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(load(second).status()).isEqualTo(PgStatus.SUCCESS);
            assertThat(CALLBACKS).containsKey(second.transactionKey());
        });
        assertThat(CALLBACKS).doesNotContainKey(first.transactionKey());
        JsonNode callback = CALLBACKS.get(second.transactionKey());
        assertThat(callback.size()).isEqualTo(3);
        assertThat(callback.get("providerOrderId").asText()).isEqualTo(second.orderId());
        assertThat(callback.get("transactionKey").asText()).isEqualTo(second.transactionKey());
        assertThat(callback.get("status").asText()).isEqualTo("SUCCESS");
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(CALLBACKS).containsKey(first.transactionKey()));
        assertThat(COUNTS.get(second.transactionKey()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("callback 유실과 수신 오류가 결제 결과를 바꾸거나 반복 발송을 만들지 않는다")
    void dropAndDeliveryFailure() {
        PgScenario dropped = PgScenario.resolve(PgScenario.Preset.NORMAL_SUCCESS,
                new PgScenario.Overrides(null, null, null, null, null, null, null, PgScenario.CallbackMode.DROP, null));
        PgTransaction lost = insert("CALLBACK-DROP-", dropped);
        PgTransaction failedDelivery = insert("CALLBACK-FAIL-", PgScenario.resolve(PgScenario.Preset.NORMAL_FAILURE, null));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(load(lost).status()).isEqualTo(PgStatus.SUCCESS);
            assertThat(load(failedDelivery).status()).isEqualTo(PgStatus.FAILED);
            assertThat(COUNTS).containsKey(failedDelivery.transactionKey());
        });
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(CALLBACKS).doesNotContainKey(lost.transactionKey());
            assertThat(COUNTS.get(failedDelivery.transactionKey()).get()).isEqualTo(1);
        });
    }

    private PgTransaction insert(String prefix, PgScenario scenario) {
        PgTransaction transaction = PgTransaction.create(prefix + UUID.randomUUID(), 7000, callbackUrl(), scenario, Instant.now());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> repository.insert(transaction));
        return transaction;
    }

    private PgTransaction load(PgTransaction transaction) {
        return new TransactionTemplate(transactionManager).execute(status -> repository.findByKey(transaction.transactionKey()).orElseThrow());
    }

    private static String callbackUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/callback";
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_EXECUTOR);
            server.createContext("/callback", exchange -> {
                try (exchange) {
                    JsonNode payload = JSON.readTree(exchange.getRequestBody());
                    String key = payload.get("transactionKey").asText();
                    CALLBACKS.put(key, payload);
                    COUNTS.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                    exchange.sendResponseHeaders(payload.get("providerOrderId").asText().startsWith("CALLBACK-FAIL-") ? 500 : 200, -1);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("테스트 callback 서버 기동 실패", exception);
        }
    }
}
