package com.loopers.interfaces.consumer;

import com.loopers.application.coupon.CampaignCache;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER,
    "kafka.topics.coupon-issues-dlt=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER_DLT,
    "coupon.issue.worker.auto-startup=true"
})
class CouponIssueListenerTest {

    private static final String TOPIC = KafkaTestContainersConfig.COUPON_ISSUES_WORKER;
    private static final String DLT = KafkaTestContainersConfig.COUPON_ISSUES_WORKER_DLT;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00.123456Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);
    private static final int PARTITION = 0;

    private final CouponFacade couponFacade;
    private final CouponIssuePublisher publisher;
    private final CouponReservation couponReservation;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final KafkaListenerEndpointRegistry listeners;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssueListenerTest(CouponFacade couponFacade, CouponIssuePublisher publisher,
                            CouponReservation couponReservation, CampaignCache campaignCache,
                            JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                            KafkaListenerEndpointRegistry listeners, KafkaTemplate<String, byte[]> kafkaTemplate,
                            DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.publisher = publisher;
        this.couponReservation = couponReservation;
        this.campaignCache = campaignCache;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.listeners = listeners;
        this.kafkaTemplate = kafkaTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
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

    private boolean hasCoupon(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ? and user_id = ?",
            Integer.class, campaignId, userId) == 1;
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaign where id = ?", Integer.class, campaignId);
    }

    private double rejectedByDb(Long campaignId) {
        return meterRegistry.counter("coupon.issue.rejected_by_db", "campaignId", String.valueOf(campaignId)).count();
    }

    private double recoveries(Long campaignId) {
        return meterRegistry.counter("coupon.issue.recovery", "campaignId", String.valueOf(campaignId)).count();
    }

    private MessageListenerContainer worker() {
        return listeners.getListenerContainer(CouponIssueListenerConfig.LISTENER_ID);
    }

    private void publishWhileWorkerIsOff(Runnable publishing) {
        worker().stop();
        try {
            publishing.run();
        } finally {
            worker().start();
        }
    }

    private void restartWorkerAndProcessAfter(Long markerCampaignId, long markerUserId, int markerNumber) {
        worker().stop();
        worker().start();
        sendToPartition(PARTITION, markerUserId, issuePayload(markerCampaignId, markerUserId, markerNumber));
        eventually(() -> hasCoupon(markerCampaignId, markerUserId));
    }

    private void sendToPartition(int partition, long userId, byte[] payload) {
        sendToPartition(partition, String.valueOf(userId), payload);
    }

    private void sendToPartition(int partition, String key, byte[] payload) {
        kafkaTemplate.send(TOPIC, partition, key, payload).join();
    }

    private static byte[] issuePayload(long campaignId, long userId, int issueNumber) {
        return ("{\"eventType\":\"CouponIssue\",\"schemaVersion\":1,\"campaignId\":" + campaignId + ",\"userId\":" + userId
            + ",\"issueNumber\":" + issueNumber + ",\"reservedAt\":\"2026-11-08T00:00:00.123456Z\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private boolean acceptedOrIssued(Long campaignId, Long userId) {
        try {
            return couponFacade.issue(campaignId, userId, NOW) instanceof CouponFacade.IssueResult.Issued;
        } catch (CoreException e) {
            return false;
        }
    }

    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WITHIN.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(WITHIN + " 안에 조건이 채워지지 않았다");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("조건을 기다리다 중단됐다", e);
            }
        }
    }

    @DisplayName("WRK-003: 접수된 메시지 하나는 같은 번호와 예약 시각을 가진 쿠폰 행 하나가 되고 발급 수가 1 오르며, 재요청은 ISSUED 다")
    @Test
    void recordsOneCouponPerMessage() {
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

    @DisplayName("WRK-004: 같은 메시지를 두 번 받아도 쿠폰은 하나이고 발급 수는 1 이다")
    @Test
    void recordsDuplicateMessageOnce() {
        Long campaignId = openCampaign();
        Long otherCampaignId = openCampaign();

        sendToPartition(PARTITION, 42L, issuePayload(campaignId, 42L, 1));
        sendToPartition(PARTITION, 42L, issuePayload(campaignId, 42L, 1));
        sendToPartition(PARTITION, 7L, issuePayload(otherCampaignId, 7L, 1));
        eventually(() -> hasCoupon(otherCampaignId, 7L));

        assertThat(couponCount(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("WRK-005: DB 가 품절이라 하면 쿠폰 없이 넘기고 지표에 그 캠페인의 수가 남으며, 워커를 다시 시작해도 다시 받지 않는다")
    @Test
    void countsRejectionByDbAndMovesOn() {
        Long campaignId = openCampaign(1);
        Long otherCampaignId = openCampaign();
        double rejectedBefore = rejectedByDb(campaignId);

        sendToPartition(PARTITION, 42L, issuePayload(campaignId, 42L, 1));
        sendToPartition(PARTITION, 7L, issuePayload(campaignId, 7L, 2));
        eventually(() -> rejectedByDb(campaignId) == rejectedBefore + 1);
        restartWorkerAndProcessAfter(otherCampaignId, 9L, 1);

        assertThat(couponCount(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(rejectedByDb(campaignId)).isEqualTo(rejectedBefore + 1);
    }

    @DisplayName("WRK-009: 없는 캠페인의 메시지는 재시도 없이 DLT 로 가고, 뒤의 메시지는 기록되며, 워커를 다시 시작해도 다시 받지 않는다")
    @Test
    void deadLettersMessageOfUnknownCampaignWithoutRetry() {
        Long campaignId = openCampaign();
        long unknownCampaignId = 999_999L;

        List<ConsumerRecord<String, byte[]>> deadLetters = TopicRecords.during(DLT, () -> {
            sendToPartition(PARTITION, 42L, issuePayload(unknownCampaignId, 42L, 1));
            sendToPartition(PARTITION, 7L, issuePayload(campaignId, 7L, 1));
            eventually(() -> hasCoupon(campaignId, 7L));
            restartWorkerAndProcessAfter(campaignId, 9L, 2);
        });

        assertThat(couponCount(campaignId)).isEqualTo(2);
        assertThat(meterRegistry.counter("coupon.issue.worker.retry", "cause", "DomainException").count()).isZero();
        assertThat(deadLetters).singleElement().satisfies(record -> {
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains("\"campaignId\":" + unknownCampaignId);
            assertThat(record.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        });
    }

    @DisplayName("WRK-009: DLT 로 간 메시지가 파티션의 마지막이어도 워커를 다시 시작하면 그 뒤부터 읽는다")
    @Test
    void commitsPastADeadLetteredRecordAtTheEndOfThePartition() {
        Long campaignId = openCampaign();
        long unknownCampaignId = 999_999L;
        int deadLettersBefore = TopicRecords.all(DLT).size();

        List<ConsumerRecord<String, byte[]>> deadLetters = TopicRecords.during(DLT, () -> {
            sendToPartition(PARTITION, 42L, issuePayload(unknownCampaignId, 42L, 1));
            eventually(() -> TopicRecords.all(DLT).size() == deadLettersBefore + 1);
            restartWorkerAndProcessAfter(campaignId, 9L, 1);
        });

        assertThat(deadLetters).singleElement().satisfies(record ->
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains("\"campaignId\":" + unknownCampaignId));
    }

    @DisplayName("WRK-005: Redis 가 받아들인 사용자를 DB 가 품절로 거절하면 캠페인을 닫고 닫힌 채 둔다. 관리자가 복구하면 DB 쿠폰으로 명단이 다시 만들어지고, 거절된 사용자들은 409 다")
    @Test
    void closesWhenDbRejectsReservedUsersAsSoldOut() {
        Long campaignId = openCampaign(1);
        double recoveriesBefore = recoveries(campaignId);
        double rejectedBefore = rejectedByDb(campaignId);
        couponReservation.restore(campaignId, List.of(new CouponReservation.Reservation(42L, 1),
            new CouponReservation.Reservation(7L, 2), new CouponReservation.Reservation(9L, 3)));

        publisher.publish(new CouponIssue(campaignId, 42L, 1, NOW));
        publisher.publish(new CouponIssue(campaignId, 7L, 2, NOW));
        publisher.publish(new CouponIssue(campaignId, 9L, 3, NOW));
        eventually(() -> couponReservation.isClosed(campaignId));
        eventually(() -> rejectedByDb(campaignId) == rejectedBefore + 2);

        assertThat(recoveries(campaignId)).isEqualTo(recoveriesBefore);
        assertThat(couponReservation.count(campaignId)).isEqualTo(3);

        assertThat(couponFacade.recover(campaignId)).isEqualTo(1);

        assertThat(recoveries(campaignId)).isEqualTo(recoveriesBefore + 1);
        assertThat(couponReservation.count(campaignId)).isEqualTo(1);
        Long issuedUser = jdbcTemplate.queryForObject("select user_id from coupon where campaign_id = ?", Long.class, campaignId);
        assertThat(issuedUser).isIn(42L, 7L, 9L);
        assertThat(acceptedOrIssued(campaignId, issuedUser)).isTrue();
        for (Long rejectedUser : List.of(42L, 7L, 9L)) {
            if (rejectedUser.equals(issuedUser)) {
                continue;
            }
            assertThatThrownBy(() -> couponFacade.issue(campaignId, rejectedUser, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.COUPON_SOLD_OUT);
        }
    }

    @DisplayName("WRK-005: Redis 가 받아들이지 않은 사용자의 메시지가 품절로 거절되면 어긋남이 아니므로 캠페인을 닫지 않는다")
    @Test
    void doesNotCloseWhenTheRejectedUserWasNeverReserved() {
        Long campaignId = openCampaign(1);
        double recoveriesBefore = recoveries(campaignId);
        double rejectedBefore = rejectedByDb(campaignId);
        couponReservation.restore(campaignId, List.of(new CouponReservation.Reservation(42L, 1)));

        publisher.publish(new CouponIssue(campaignId, 42L, 1, NOW));
        eventually(() -> couponCount(campaignId) == 1);
        publisher.publish(new CouponIssue(campaignId, 7L, 2, NOW));
        eventually(() -> rejectedByDb(campaignId) == rejectedBefore + 1);

        assertThat(couponReservation.isClosed(campaignId)).isFalse();
        assertThat(recoveries(campaignId)).isEqualTo(recoveriesBefore);
        assertThat(couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CouponFacade.IssueResult.Issued.class)
            .extracting("issueNumber").isEqualTo(1);
    }

    @DisplayName("WRK-011: 워커를 끈 동안 한 파티션에 쌓인 정상, 없는 캠페인, 정상 메시지가 한 번에 오면 앞뒤는 기록되고 가운데만 DLT 로 가며, 워커를 다시 시작해도 다시 받지 않는다")
    @Test
    void deadLettersOnlyTheRejectedRecordInTheMiddleOfABatch() {
        Long campaignId = openCampaign();
        long unknownCampaignId = 999_999L;

        List<ConsumerRecord<String, byte[]>> deadLetters = TopicRecords.during(DLT, () -> {
            publishWhileWorkerIsOff(() -> {
                sendToPartition(PARTITION, 42L, issuePayload(campaignId, 42L, 1));
                sendToPartition(PARTITION, 7L, issuePayload(unknownCampaignId, 7L, 1));
                sendToPartition(PARTITION, 9L, issuePayload(campaignId, 9L, 2));
            });
            eventually(() -> couponCount(campaignId) == 2);
            restartWorkerAndProcessAfter(campaignId, 11L, 3);
        });

        assertThat(couponCount(campaignId)).isEqualTo(3);
        assertThat(issuedCount(campaignId)).isEqualTo(3);
        assertThat(deadLetters).singleElement().satisfies(record ->
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains("\"campaignId\":" + unknownCampaignId));
    }

    @DisplayName("WRK-011: 워커를 끈 동안 쌓인 정상, 읽을 수 없는, 정상 메시지가 한 번에 오면 앞뒤는 기록되고 읽을 수 없는 것만 DLT 로 가며, 워커를 다시 시작해도 다시 받지 않는다")
    @Test
    void deadLettersOnlyTheUnreadableRecordInTheMiddleOfABatch() {
        Long campaignId = openCampaign();
        byte[] unreadable = "not a coupon issue".getBytes(StandardCharsets.UTF_8);

        List<ConsumerRecord<String, byte[]>> deadLetters = TopicRecords.during(DLT, () -> {
            publishWhileWorkerIsOff(() -> {
                sendToPartition(PARTITION, 42L, issuePayload(campaignId, 42L, 1));
                sendToPartition(PARTITION, "unreadable", unreadable);
                sendToPartition(PARTITION, 9L, issuePayload(campaignId, 9L, 2));
            });
            eventually(() -> couponCount(campaignId) == 2);
            restartWorkerAndProcessAfter(campaignId, 11L, 3);
        });

        assertThat(couponCount(campaignId)).isEqualTo(3);
        assertThat(issuedCount(campaignId)).isEqualTo(3);
        assertThat(deadLetters).singleElement().satisfies(record ->
            assertThat(Arrays.equals(record.value(), unreadable)).isTrue());
    }
}
