package com.loopers.domains.order.in;

import com.loopers.E2eApiTestApplication;
import com.loopers.E2eBatchContext;
import com.loopers.E2eBatchContext.HookedRankingRepository;
import com.loopers.E2eStreamerContext;
import com.loopers.E2eStreamerContext.ConsumeObservation;
import com.loopers.E2eStreamerContext.ConsumeObservation.Consumed;
import com.loopers.application.purchase.ConsumeResult;
import com.loopers.batch.job.ranking.WeeklyRankingLauncher;
import com.loopers.domains.order.app.OrderApplicationService.ItemRequest;
import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.interfaces.consumer.OrderEventsListener;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingOrderConfirmedOutbox;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 9주차 4단계: 주문 확정 → Outbox → relay → Kafka → 실제 streamer 구매 활동 → 실제 batch 런처·주간 랭킹 job → 고객 랭킹 API를 한 JVM에서 본다.
// streamer·batch는 같은 JVM의 별도 컨텍스트(E2eStreamerContext·E2eBatchContext)다. 다른 JVM·커넥션 풀일 때의 타이밍은 증명하지 못한다.
// 대역은 markPublished 실패 hook(재발행 재현), handler 관찰 래퍼, batch의 공개 직전 실패 hook과 고정 시각뿐이다.
// 기대값은 테스트 clock(확정 시각)과 이 테스트가 만든 주문의 수량으로 계산한다.
@SpringBootTest(classes = E2eApiTestApplication.class)
class OrderToWeeklyRankingE2ETest extends OrderEventsIntegrationSupport {
    private static final Duration ASSIGNMENT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(60);
    // 2026-10-26(월) 00:00 UTC ~ 2026-11-02(월) 00:00 UTC(제외)
    private static final LocalDate WEEK = LocalDate.parse("2026-10-26");
    private static final LocalDate PREVIOUS_WEEK = LocalDate.parse("2026-10-19");
    private static final LocalDate EMPTY_WEEK = LocalDate.parse("2026-10-12");

    @Autowired private OutboxRelaySettings outboxRelaySettings;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Environment environment;

    private ConfigurableApplicationContext streamer;
    private ConfigurableApplicationContext batch;
    private WeeklyRankingLauncher launcher;
    private HookedRankingRepository rankingRepository;
    private long shoes;
    private long socks;
    private long cap;
    private long bag;

    @BeforeEach
    void startContexts() {
        ConsumeObservation.CONSUMED.clear();
        String url = environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url");
        String username = environment.getRequiredProperty("datasource.mysql-jpa.main.username");
        String password = environment.getRequiredProperty("datasource.mysql-jpa.main.password");
        streamer = E2eStreamerContext.start(TOPIC, bootstrapServers, url, username, password);
        batch = E2eBatchContext.start(url, username, password);
        launcher = batch.getBean(WeeklyRankingLauncher.class);
        rankingRepository = batch.getBean(HookedRankingRepository.class);
        long brand = brands.create("랭킹 브랜드").id();
        shoes = products.create(brand, "신발", 10000, 50).id();
        socks = products.create(brand, "양말", 2000, 50).id();
        cap = products.create(brand, "모자", 5000, 50).id();
        bag = products.create(brand, "가방", 30000, 50).id();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
    }

    @AfterEach
    void stopContexts() {
        if (rankingRepository != null) {
            rankingRepository.disarm();
        }
        if (streamer != null) {
            streamer.close();
        }
        if (batch != null) {
            // 이 테스트가 쓴 주의 랭킹 행만 지운다. Batch metadata는 이 테스트만 쓰는 job이라 남겨도 다른 테스트에 영향이 없다.
            for (LocalDate week : List.of(WEEK, PREVIOUS_WEEK, EMPTY_WEEK)) {
                String prefix = "weekly-" + week.toString().replace("-", "") + "-r%";
                jdbc.update("delete from ranking_publication where period_start = ?", week);
                jdbc.update("delete from ranking_result where snapshot_id like ?", prefix);
                jdbc.update("delete from ranking_score_stage where snapshot_id like ?", prefix);
                jdbc.update("delete from ranking_snapshot_input where snapshot_id like ?", prefix);
                jdbc.update("delete from ranking_snapshot where snapshot_id like ?", prefix);
            }
            batch.close();
        }
        ConsumeObservation.CONSUMED.clear();
    }

    @Test
    @DisplayName("주문 확정부터 랭킹 API까지: 재발행돼도 점수가 한 번만 세지고, 구간 시작은 포함·끝은 제외되며, r1 고정 뒤 늦은 구매는 r2가 공개될 때만 보이고, 삭제 상품은 빠지고, 빈 주는 빈 공개본·미실행 주는 미공개다")
    void orderToWeeklyRanking() throws Exception {
        MessageListenerContainer listener = streamer.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer(OrderEventsListener.LISTENER_ID);
        awaitUntil("consumer topic partition 배정", ASSIGNMENT_DEADLINE, () -> assignedToTopic(listener),
            () -> "assigned=" + listener.getAssignedPartitions());

        // 1) 구간 시작 정각 확정(포함): 신발 2·양말 1. 같은 주문이 재발행돼 broker에 2건이 된다.
        long atStart = confirmedOrder("2026-10-26T00:00:00Z", Map.of(shoes, 2, socks, 1));
        failingOutbox.failOnNextMarkPublished();
        assertThatThrownBy(relay::runOnce).hasMessage(FailingOrderConfirmedOutbox.INJECTED_MARK_PUBLISHED_FAILURE);
        new OrderOutboxRelay(failingOutbox, sender, outboxRelaySettings, transactionTemplate, clock).runOnce();
        // 2) 주중: 양말 3·모자 1. 3) 구간 끝 정각(제외)과 시작 직전(이전 주): 가방.
        long midWeek = confirmedOrder("2026-10-29T01:00:00Z", Map.of(socks, 3, cap, 1));
        long atEnd = confirmedOrder("2026-11-02T00:00:00Z", Map.of(bag, 5));
        long beforeStart = confirmedOrder("2026-10-25T23:59:59Z", Map.of(bag, 7));
        // relay는 다음 시도 시각이 지금(clock) 이하인 행만 보내므로 가장 늦은 확정 시각으로 돌려 둔다.
        clock.set(Instant.parse("2026-11-02T00:00:00Z"));
        relay.runOnce();
        awaitConsumed(atStart, midWeek, atEnd, beforeStart);
        awaitUntil("재발행 record까지 처리", RESULT_DEADLINE,
            () -> consumedOf(atStart).stream().map(Consumed::offset).distinct().count() >= 2, () -> "" + consumedOf(atStart));

        assertThat(consumedOf(atStart)).filteredOn(entry -> entry.result() == ConsumeResult.RECORDED).hasSize(1);
        assertThat(activity(atStart)).containsExactlyInAnyOrderEntriesOf(Map.of(shoes, 2, socks, 1));

        // 미실행 주는 미공개다.
        weekly(WEEK, 10).andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED")).andExpect(jsonPath("$.data.items", hasSize(0)));

        // 4) 런처 r1: 양말 1+3=4, 신발 2, 모자 1. 재발행돼도 신발은 2(4가 아님). 끝 정각·이전 주 가방은 없다.
        JobExecution r1 = launcher.launch(WEEK, null);
        assertThat(r1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(r1.getJobParameters().getString("snapshotId")).isEqualTo("weekly-20261026-r1");
        expectRanking("weekly-20261026-r1", List.of(socks, shoes, cap), List.of(4, 2, 1));
        weekly(WEEK, 10).andExpect(jsonPath("$.data.frozenAt").value(E2eBatchContext.FROZEN_AT.toString()))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-11-02"));

        // 5) r1 고정 뒤 같은 주 늦은 구매: 신발 5. 구매 활동에는 들어가지만 r1은 그대로다.
        long late = confirmedOrder("2026-10-30T09:00:00Z", Map.of(shoes, 5));
        relay.runOnce();
        awaitConsumed(late);
        expectRanking("weekly-20261026-r1", List.of(socks, shoes, cap), List.of(4, 2, 1));

        // 6) 런처 r2: 공개 직전에 실패시켜 고정·계산만 끝난 상태에서는 API가 r1이다.
        rankingRepository.failNextPublish();
        JobExecution r2Failed = launcher.launch(WEEK, null);
        assertThat(r2Failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(r2Failed.getJobParameters().getString("snapshotId")).isEqualTo("weekly-20261026-r2");
        expectRanking("weekly-20261026-r1", List.of(socks, shoes, cap), List.of(4, 2, 1));

        // 7) 런처를 다시 부르면 같은 r2를 재시작해 공개한다. 늦은 구매가 들어가 신발 7이 1위다.
        JobExecution r2 = launcher.launch(WEEK, null);
        assertThat(r2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(r2.getJobInstance().getInstanceId()).isEqualTo(r2Failed.getJobInstance().getInstanceId());
        expectRanking("weekly-20261026-r2", List.of(shoes, socks, cap), List.of(7, 4, 1));

        // 8) 양말이 삭제되면 응답에서 빠지고 표시 순위를 다시 매긴다. limit은 거른 뒤 채운다.
        products.delete(socks);
        weekly(WEEK, 2)
            .andExpect(jsonPath("$.data.items[*].productId").value(contains((int) shoes, (int) cap)))
            .andExpect(jsonPath("$.data.items[*].rank").value(contains(1, 2)));

        // 9) 구매가 없는 주를 실행하면 빈 공개본, 실행하지 않은 이전 주(가방 7이 있는 주)는 미공개다.
        assertThat(launcher.launch(EMPTY_WEEK, null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        weekly(EMPTY_WEEK, 10).andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.items", hasSize(0)));
        weekly(PREVIOUS_WEEK, 10).andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"));
    }

    private long confirmedOrder(String confirmedAt, Map<Long, Integer> items) throws Exception {
        clock.set(Instant.parse(confirmedAt));
        long orderId = orders.create(1, items.entrySet().stream()
            .map(item -> new ItemRequest(item.getKey(), item.getValue())).toList()).id();
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        return orderId;
    }

    private void expectRanking(String snapshotId, List<Long> productIds, List<Integer> scores) throws Exception {
        weekly(WEEK, 10)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId))
            .andExpect(jsonPath("$.data.items[*].productId").value(contains(productIds.stream().map(Long::intValue).toArray())))
            .andExpect(jsonPath("$.data.items[*].score").value(contains(scores.toArray())));
    }

    private ResultActions weekly(LocalDate week, int limit) throws Exception {
        return mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", week.toString())
                .param("limit", String.valueOf(limit)).header("X-USER-ID", "1"))
            .andExpect(status().isOk());
    }

    private void awaitConsumed(long... orderIds) {
        for (long orderId : orderIds) {
            awaitUntil("구매 활동 기록 order=" + orderId, RESULT_DEADLINE,
                () -> consumedOf(orderId).stream().anyMatch(entry -> entry.result() == ConsumeResult.RECORDED),
                () -> "consumed=" + ConsumeObservation.CONSUMED);
        }
    }

    private List<Consumed> consumedOf(long orderId) {
        return ConsumeObservation.CONSUMED.stream().filter(entry -> eventId(orderId).equals(entry.eventId())).toList();
    }

    private Map<Long, Integer> activity(long orderId) {
        return jdbc.queryForList("select product_id, quantity from product_purchase_activity where order_id = ?", orderId).stream()
            .collect(Collectors.toMap(row -> ((Number) row.get("product_id")).longValue(),
                row -> ((Number) row.get("quantity")).intValue()));
    }

    private void awaitUntil(String step, Duration limit, BooleanSupplier condition, Supplier<String> state) {
        Instant deadline = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail(step + " 대기 " + limit.toSeconds() + "초 초과. 실제 상태: " + state.get());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(step + " 대기 중 인터럽트. 실제 상태: " + state.get());
            }
        }
    }

    private static boolean assignedToTopic(MessageListenerContainer listener) {
        Collection<TopicPartition> assigned = listener.getAssignedPartitions();
        return assigned != null && assigned.stream().anyMatch(partition -> TOPIC.equals(partition.topic()));
    }
}
