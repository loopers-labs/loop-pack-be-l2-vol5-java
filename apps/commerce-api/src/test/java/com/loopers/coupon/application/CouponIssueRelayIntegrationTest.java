package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase;
import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase.RelayResult;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 당첨 기록 (D-2): 같은 사람은 몇 번 기록해도 한 행이고, relay가 broker 응답을 받은 뒤에만 PUBLISHED로 바꾼다.
 */
@SpringBootTest
class CouponIssueRelayIntegrationTest {

    @Autowired
    private CouponIssueOutboxPort outboxPort;

    @Autowired
    private CouponIssueRelayUseCase relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${kafka.topics.coupon-issue}")
    private String topic;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private long rows(long campaignId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox WHERE campaign_id = ?", Long.class, campaignId);
    }

    @DisplayName("같은 사람을 여러 번(동시에도) 기록하면 처음 한 번만 true이고 행은 하나다 — 앞으로 채우기가 겹쳐도 꼬이지 않는다.")
    @Test
    void appendIsIdempotentEvenConcurrently() throws Exception {
        ZonedDateTime now = ZonedDateTime.now();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return outboxPort.appendIfAbsent(1L, 42L, now);
            }));
        }
        start.countDown();
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        assertThat(results).containsOnlyOnce(true);
        assertThat(rows(1L)).isEqualTo(1);
        assertThat(outboxPort.findUserIds(1L)).containsExactly(42L);
    }

    @DisplayName("relay 한 회차가 기록을 보내고 PUBLISHED로 바꾼다. topic에는 key = userId, 값 {campaignId, userId}로 남는다.")
    @Test
    void relaysToKafka() {
        long campaignId = System.nanoTime() % 1_000_000 + 1_000;
        ZonedDateTime now = ZonedDateTime.now();
        outboxPort.appendIfAbsent(campaignId, 7L, now);
        outboxPort.appendIfAbsent(campaignId, 8L, now);

        RelayResult result = relay.relayOnce();

        assertThat(result.published()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM coupon_issue_outbox WHERE campaign_id = ?", String.class, campaignId))
            .containsOnly("PUBLISHED");
        String expected = "{\"campaignId\":" + campaignId + ",\"userId\":7}";
        List<ConsumerRecord<String, String>> records = KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), topic,
            record -> expected.equals(record.value()), 1, Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("7");
    }
}
