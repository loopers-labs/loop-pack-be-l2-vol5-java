package com.loopers.interfaces.consumer;

import com.loopers.application.coupon.CampaignCache;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER,
    "coupon.issue.worker.auto-startup=true"
})
class CouponIssueListenerTest {

    private static final String TOPIC = KafkaTestContainersConfig.COUPON_ISSUES_WORKER;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00.123456Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);

    private final CouponFacade couponFacade;
    private final CouponIssuePublisher publisher;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssueListenerTest(CouponFacade couponFacade, CouponIssuePublisher publisher, CampaignCache campaignCache,
                            JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                            DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.publisher = publisher;
        this.campaignCache = campaignCache;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    private Long openCampaign() {
        return openCampaign(100);
    }

    private Long openCampaign(int quota) {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(quota), true).getId();
    }

    private int couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ?", Integer.class, campaignId);
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaign where id = ?", Integer.class, campaignId);
    }

    private static boolean everyRecordCommitted() {
        Map<String, Object> bootstrap = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaTestContainersConfig.bootstrapServers());
        try (Admin admin = Admin.create(bootstrap);
             KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                 ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()),
                 new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(info -> new TopicPartition(TOPIC, info.partition()))
                .toList();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                .listConsumerGroupOffsets(CouponIssueListenerConfig.GROUP)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            return partitions.stream().allMatch(partition -> end.get(partition) == 0
                || (committed.get(partition) != null && committed.get(partition).offset() == end.get(partition)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("consumer group 의 offset 을 읽다 중단됐다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("consumer group 의 offset 을 읽지 못했다", e);
        }
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WITHIN.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(WITHIN + " 안에 조건이 채워지지 않았다");
            }
            Thread.sleep(100);
        }
    }

    @DisplayName("WRK-003: 접수된 메시지 하나는 같은 번호와 예약 시각을 가진 쿠폰 행 하나가 되고 발급 수가 1 오르며, 재요청은 ISSUED 다")
    @Test
    void recordsOneCouponPerMessage() throws Exception {
        Long campaignId = openCampaign();

        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(1));
        eventually(() -> couponCount(campaignId) == 1);

        Map<String, Object> coupon = jdbcTemplate.queryForMap(
            "select issue_number, issued_at from coupon where campaign_id = ? and user_id = ?", campaignId, 42L);
        assertThat(coupon.get("issue_number")).isEqualTo(1);
        assertThat(((LocalDateTime) coupon.get("issued_at")).toInstant(ZoneOffset.UTC)).isEqualTo(NOW);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isInstanceOf(CouponFacade.IssueResult.Issued.class);
    }

    @DisplayName("WRK-004: 같은 메시지를 두 번 받아도 쿠폰은 하나이고 발급 수는 1 이며, 두 번째도 offset 을 넘긴다")
    @Test
    void recordsDuplicateMessageOnce() throws Exception {
        Long campaignId = openCampaign();
        CouponIssue issue = new CouponIssue(campaignId, 42L, 1, NOW);

        publisher.publish(issue);
        publisher.publish(issue);
        eventually(CouponIssueListenerTest::everyRecordCommitted);

        assertThat(couponCount(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("WRK-005: DB 가 품절이라 하면 쿠폰 없이 넘기고 지표에 그 캠페인의 수가 남으며, offset 은 넘어간다")
    @Test
    void countsRejectionByDbAndMovesOn() throws Exception {
        Long campaignId = openCampaign(1);

        publisher.publish(new CouponIssue(campaignId, 42L, 1, NOW));
        publisher.publish(new CouponIssue(campaignId, 7L, 2, NOW));
        eventually(CouponIssueListenerTest::everyRecordCommitted);

        assertThat(couponCount(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(meterRegistry.counter("coupon.issue.rejected_by_db", "campaignId", String.valueOf(campaignId)).count())
            .isEqualTo(1.0);
    }
}
