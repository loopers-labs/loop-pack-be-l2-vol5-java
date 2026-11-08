package com.loopers.infrastructure.order.outbox;

import com.loopers.application.time.ApplicationTime;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 인스턴스마다 Relay를 켜는 배포를 같은 DB를 쓰는 Relay 객체 두 개로 표현한다. Kafka 전송만 대역으로 바꾼다.
@SpringBootTest
class OrderConfirmedOutboxMultiRelayIntegrationTest {

    private static final String TOPIC = "order-events";
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 8, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final int ROWS = 30;

    @Autowired private OrderConfirmedOutboxStateService stateService;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private final ExecutorService instances = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        instances.shutdownNow();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 인스턴스의 Relay가 동시에 실행돼도 행마다 한 번만 보내고 모두 PUBLISHED로 끝난다.")
    @Test
    void publishesEachRowOnceWhenRelaysRunConcurrently() throws Exception {
        insertPendingRows();
        Map<String, AtomicInteger> sendsByKey = new ConcurrentHashMap<>();
        Set<String> sendingInstances = ConcurrentHashMap.newKeySet();
        OrderConfirmedKafkaSender sender = mock(OrderConfirmedKafkaSender.class);
        when(sender.send(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            sendsByKey.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            sendingInstances.add(Thread.currentThread().getName());
            return CompletableFuture.supplyAsync(() -> sent(key),
                CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS));
        });

        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (OrderConfirmedOutboxRelay relay : List.of(relay(sender), relay(sender))) {
            runs.add(instances.submit(() -> {
                start.await();
                while (!stateService.findDueEventIds(NOW, 1).isEmpty()) {
                    relay.runDueBatch();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(30, TimeUnit.SECONDS);
        }

        assertThat(sendsByKey).hasSize(ROWS);
        assertThat(sendsByKey.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(sendingInstances).hasSize(2);
        assertThat(jdbcTemplate.queryForList(
            "SELECT DISTINCT status FROM order_confirmed_outbox", String.class)).containsExactly("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT SUM(attempt_count) FROM order_confirmed_outbox", Long.class)).isEqualTo(ROWS);
    }

    private OrderConfirmedOutboxRelay relay(OrderConfirmedKafkaSender sender) {
        return new OrderConfirmedOutboxRelay(
            stateService,
            sender,
            new OrderConfirmedOutboxFailureClassifier(),
            new ApplicationTime(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)),
            meterRegistry,
            10,
            3,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(6)
        );
    }

    private SendResult<Object, Object> sent(String key) {
        return new SendResult<>(new ProducerRecord<>(TOPIC, key, "{}"),
            new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0));
    }

    private void insertPendingRows() {
        LocalDateTime now = NOW.toLocalDateTime();
        List<Object[]> rows = IntStream.rangeClosed(1, ROWS)
            .mapToObj(id -> new Object[]{"order-confirmed:v1:" + id, id, Integer.toString(id), now, now, now})
            .toList();
        jdbcTemplate.batchUpdate("""
            INSERT INTO order_confirmed_outbox
              (event_id, order_id, event_type, schema_version, message_key, payload, status,
               attempt_count, cycle_attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, 'OrderConfirmed', 1, ?, '{}', 'PENDING', 0, 0, ?, ?, ?)
            """, rows);
    }
}
