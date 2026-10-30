package com.loopers.interfaces.consumer;

import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.loopers.support.OrderConfirmedMessages.json;
import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너를 멈추는 장면이라 다른 listener 테스트와 topic·context를 나눈다.
@SpringBootTest
@Import(OrderEventsListenerUnsupportedVersionTest.CountingParserConfig.class)
class OrderEventsListenerUnsupportedVersionTest {

    private static final String TOPIC = "order-events-version-" + UUID.randomUUID();

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private CountingParser countingParser;

    private OrderEventsListenerFixture fixture;
    private PurchaseActivityTables tables;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
    }

    @BeforeEach
    void setUp() {
        fixture = new OrderEventsListenerFixture(bootstrapServers, TOPIC, kafkaListenerEndpointRegistry);
        tables = new PurchaseActivityTables(jdbcTemplate);
        fixture.awaitAssigned();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("처음 보는 schemaVersion이 오면 재시도 없이 컨테이너가 멈추고 committed offset은 그 record의 offset n 그대로다")
    @Test
    void unsupportedVersionStopsContainerWithoutCommit() throws Exception {
        long warmUp = fixture.send("41", json("evt-41", 41, 7, 1));
        fixture.awaitCommitted(warmUp + 1);

        long n = fixture.send("42", json(2, "evt-42", 42, 7, 2));
        fixture.awaitStopped();

        System.out.println("[실험③ 버전] warm-up committed=" + (warmUp + 1) + ", n=" + n + ", 정지 후 committed=" + fixture.committed()
            + ", v2 record 전달 횟수=" + countingParser.parsedWithKey("42"));
        assertThat(n).isEqualTo(warmUp + 1);
        assertThat(countingParser.parsedWithKey("42")).isEqualTo(1);
        assertThat(fixture.committed()).isEqualTo(n);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activitiesOf(42)).isEmpty();
        assertThat(tables.anomalies()).isZero();
    }

    @TestConfiguration
    static class CountingParserConfig {

        @Bean
        @Primary
        CountingParser countingParser() {
            return new CountingParser();
        }
    }

    // listener가 같은 record를 몇 번 받았는지 세기 위해 실제 파서를 그대로 쓰며 key별 호출만 센다.
    static class CountingParser extends OrderConfirmedEventParser {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<String, AtomicInteger>();

        @Override
        public OrderEventParseResult parse(String key, byte[] payload) {
            calls.computeIfAbsent(String.valueOf(key), ignored -> new AtomicInteger()).incrementAndGet();
            return super.parse(key, payload);
        }

        int parsedWithKey(String key) {
            AtomicInteger count = calls.get(key);
            return count == null ? 0 : count.get();
        }
    }
}
