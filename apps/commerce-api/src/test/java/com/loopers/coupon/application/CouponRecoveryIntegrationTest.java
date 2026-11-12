package com.loopers.coupon.application;

import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.application.port.in.CouponIssueUseCase;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.IssueDecision;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.support.CouponTestFixtures;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.loopers.utils.KafkaTopicReader.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * 앞으로 채우기 (D-2): 당첨자 Set이 원본이고 outbox가 따라잡는다. 그리고 DLT 재처리.
 */
// 이 클래스만 쓰는 설정(속성·mock)이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 닫아 커넥션 풀(최소 30개)을 돌려준다 —
// 닫지 않으면 캐시된 컨텍스트들의 커넥션이 MySQL max_connections(151)를 넘어 다른 테스트가 뜨지 못했다(11-12).
@DirtiesContext
@SpringBootTest
class CouponRecoveryIntegrationTest {

    @Autowired
    private CouponIssueUseCase issueUseCase;

    @Autowired
    private CouponReconcileService reconcileService;

    @Autowired
    private CouponDeadLetterReplayService deadLetterReplayService;

    @Autowired
    private QueueAdmissionService admissionService;

    @Autowired
    private OpenCampaigns openCampaigns;

    @Autowired
    private SoldOutFlags soldOutFlags;

    @Autowired
    private CouponGatePort gate;

    @MockitoSpyBean
    private CouponIssueOutboxPort outboxPort;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @Value("${kafka.topics.coupon-issue}")
    private String topic;

    @AfterEach
    void tearDown() {
        reset(outboxPort);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        openCampaigns.refresh();
        soldOutFlags.clear();
    }

    private CouponCampaign open(int total) {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, total, null);
        openCampaigns.refresh();
        admissionService.recover(campaign.getId());
        return campaign;
    }

    private long recorded(Long campaignId, Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox WHERE campaign_id = ? AND user_id = ?",
            Long.class, campaignId, userId);
    }

    @DisplayName("필수 실험 ④ 당첨 뒤 기록이 실패하면 503이고 당첨자 Set에는 남는다. 맞춰 보기가 기록을 채우고, 다시 요청하면 202다.")
    @Test
    void recordFailureIsFilledForward() {
        CouponCampaign campaign = open(5);
        gate.register(campaign.getId(), 42L, 5, 50); // 바로 입장
        doThrow(new DataAccessResourceFailureException("MySQL down")).when(outboxPort).appendIfAbsent(eq(campaign.getId()), eq(42L), any());

        assertThatThrownBy(() -> issueUseCase.issue(campaign.getId(), 42L))
            .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
        assertThat(gate.winners(campaign.getId())).containsExactly(42L);
        assertThat(recorded(campaign.getId(), 42L)).isZero();

        reset(outboxPort); // MySQL 복구
        assertThat(reconcileService.fillMissingRecords(campaign.getId())).isEqualTo(1);
        assertThat(recorded(campaign.getId(), 42L)).isEqualTo(1);

        assertThat(issueUseCase.issue(campaign.getId(), 42L).status()).isEqualTo(CouponIssueUseCase.IssueOutcome.Status.ACCEPTED);
        assertThat(recorded(campaign.getId(), 42L)).isEqualTo(1);
        assertThat(gate.issue(campaign.getId(), 42L, 5)).isEqualTo(IssueDecision.ALREADY);
    }

    @DisplayName("이미 기록된 당첨자는 맞춰 보기가 다시 쓰지 않는다.")
    @Test
    void reconcileSkipsRecorded() {
        CouponCampaign campaign = open(5);
        gate.register(campaign.getId(), 7L, 5, 50);
        issueUseCase.issue(campaign.getId(), 7L);

        assertThat(reconcileService.fillMissingRecords(campaign.getId())).isZero();
        assertThat(recorded(campaign.getId(), 7L)).isEqualTo(1);
    }

    @DisplayName("DLT 재처리는 이 캠페인의 DLT 메시지만 원래 topic으로 다시 보낸다.")
    @Test
    void replaysOnlyThisCampaignsDeadLetters() throws Exception {
        CouponCampaign campaign = open(5);
        long otherCampaign = campaign.getId() + 1_000_000;
        String mine = "{\"campaignId\":" + campaign.getId() + ",\"userId\":" + System.nanoTime() + "}";
        String other = "{\"campaignId\":" + otherCampaign + ",\"userId\":1}";
        // DLT topic은 streamer(consumer 쪽)가 만든다. api 테스트 클러스터에는 직접 만든다
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic + ".DLT", 3, (short) 3))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic + ".DLT", "1", mine)).get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic + ".DLT", "1", other)).get(10, TimeUnit.SECONDS);
        }

        int replayed = deadLetterReplayService.replay(campaign.getId());

        assertThat(replayed).isGreaterThanOrEqualTo(1);
        assertThat(read(KafkaTestContainersConfig.bootstrapServers(), topic, r -> mine.equals(r.value()), 1, Duration.ofSeconds(20)))
            .hasSize(1);
        assertThat(read(KafkaTestContainersConfig.bootstrapServers(), topic, r -> other.equals(r.value()), 1, Duration.ofSeconds(3)))
            .isEmpty();
    }

    @DisplayName("마감 + 10분이 지난 캠페인의 Redis 키를 정리한다.")
    @Test
    void cleansUpAfterDeadline() {
        ZonedDateTime now = ZonedDateTime.now();
        CouponCampaign ended = campaignJpaRepository.save(new CouponCampaign("어제", 5, null, now.minusHours(2), now.minusMinutes(20)));
        gate.rebuild(ended.getId(), java.util.Set.of(1L, 2L));

        reconcileService.run();

        assertThat(gate.issue(ended.getId(), 1L, 5)).isEqualTo(IssueDecision.UNKNOWN);
        assertThat(gate.winners(ended.getId())).isEmpty();
    }

    @DisplayName("마감 뒤 10분이 안 지난 캠페인은 정리하지 않는다 — 맞춰 보기가 마감 직전 당첨자의 기록을 채울 여유 (D-4-6).")
    @Test
    void keepsKeysWithinTenMinutesAfterDeadline() {
        ZonedDateTime now = ZonedDateTime.now();
        CouponCampaign justEnded = campaignJpaRepository.save(new CouponCampaign("방금", 5, null, now.minusHours(1), now.minusMinutes(5)));
        gate.rebuild(justEnded.getId(), java.util.Set.of(1L));

        reconcileService.run();

        assertThat(gate.winners(justEnded.getId())).containsExactly(1L);
        assertThat(recorded(justEnded.getId(), 1L)).isEqualTo(1); // 기록이 없던 당첨자를 채웠다
    }
}
