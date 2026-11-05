package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedBackfill;
import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.PgOrderId;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.w6.W6DirectPublicationConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * §5 W6 누락 상태를 새 격리 실행에서 재현하고 같은 실행 안에서 복구한다. 역사적 W6 원본 행의 복원이 아니라 이번 실행의 사건이다.
 * <ol>
 *   <li>준비 (W6 revision 의 확정·after-commit 직접 발행 경로, Outbox 테이블 없음): 기존 주문 API 와 로컬 PG fixture 로 합성 주문 A·B 를 확정한다.
 *       A 는 확정 commit 뒤 send 직전에 멈추고, B 는 실제 발행·소비까지 끝낸다.</li>
 *   <li>W6 api context 를 닫는다 (직접 발행 중단). 같은 DB 에 W7 배포 SQL 로 Outbox 테이블을 만들고 W7 api context 를 띄운다 (스키마 validate).</li>
 *   <li>복구: A·B 의 저장된 확정 사실로 없는 Outbox 를 넣고 relay 를 돌린다. 구매 활동 consumer 는 처음부터 끝까지 같은 context 로 돈다.</li>
 * </ol>
 * 준비와 복구 사이에 DB·broker·PG 상태를 초기화하지 않는다. 컨테이너는 이 클래스의 JVM 에만 있다 (forkEvery = 1).
 * 실행: ./gradlew :tests:commerce-e2e:test --tests '*W6MissingEventRecoveryTest' (Docker 필요).
 */
class W6MissingEventRecoveryTest {
    private static final Logger log = LoggerFactory.getLogger(W6MissingEventRecoveryTest.class);
    private static final String TOPIC = "order-events-w6-recovery";
    private static final String GROUP = "purchase-activity-w6-recovery";

    private ConfigurableApplicationContext streamer;
    private ConfigurableApplicationContext w6Api;
    private ConfigurableApplicationContext w7Api;

    /** 이번 실행의 확정 사건 하나 (준비에서 관찰한 값). */
    private record Case(Long orderId, String providerOrderId, String transactionKey, String eventId, String confirmedAt,
                        List<Map<String, Object>> items, Map<String, Object> orderRow, List<Map<String, Object>> ledger) {
    }

    @AfterEach
    void tearDown() {
        W6StopBeforeSendConfig.STOP_BEFORE_SEND.clear();
        E2eContexts.closeQuietly(w7Api);
        E2eContexts.closeQuietly(w6Api);
        E2eContexts.closeQuietly(streamer);
    }

    @DisplayName("[§5] W6 경로로 만든 미발행(A)·소비 완료(B) 확정을 W7 Outbox 로 복구하면 A 의 구매 활동만 새로 생기고 B 는 늘지 않는다. 복구는 PG·주문·예약을 건드리지 않고, 두 번째 복구도 아무것도 늘리지 않는다.")
    @Test
    void recoversMissingW6Event_andKeepsConsumedOneUnchanged() {
        E2eContexts.startContainers();
        E2eDb db = new E2eDb();

        // ---- 준비: W6 경로 ----
        w6Api = E2eContexts.startApi(TOPIC, List.of(
                "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider=" + ExcludeOutboxSchemaFilterProvider.class.getName()),
            W6DirectPublicationConfig.class, W6StopBeforeSendConfig.class);
        assertThat(db.tableExists("order_event_outbox")).as("W6 스키마에는 Outbox 테이블이 없다").isFalse();
        streamer = E2eContexts.startStreamer(TOPIC, GROUP);

        Fixtures fixtures = w6Api.getBean(Fixtures.class);
        fixtures.catalogRevision();
        UserModel user = fixtures.user();
        BrandModel brand = fixtures.brand("브랜드");
        ProductModel productA = fixtures.product(brand.getId(), "상품 A", 1_000L, 10);
        ProductModel productB = fixtures.product(brand.getId(), "상품 B", 500L, 10);
        ApiTestClient api = apiClient(w6Api);
        FakePaymentGateway w6Pg = w6Api.getBean(FakePaymentGateway.class);

        Long orderA = createOrder(api, user, productA, productB);
        Long orderB = createOrder(api, user, productA, productB);
        W6StopBeforeSendConfig.STOP_BEFORE_SEND.add(orderA);
        confirm(api, user, orderA);
        confirm(api, user, orderB);

        E2eKafka.await("B 의 구매 활동 저장", () -> db.processedOfOrder(orderB).size() == 1, () -> "processed(B) = " + db.processedOfOrder(orderB));
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);

        Case a = observe(w6Api, db, orderA);
        Case b = observe(w6Api, db, orderB);
        for (Case c : List.of(a, b)) {
            assertThat(c.orderRow().get("status")).isEqualTo("CONFIRMED");
            assertThat(c.ledger()).extracting(row -> row.get("type")).containsExactly("RESERVE", "RESERVE");
        }
        assertThat(db.tableExists("order_event_outbox")).isFalse();
        assertThat(db.activityOfOrder(orderA)).as("A 는 send 전에 멈춰 구매 활동이 없다").isEmpty();
        assertThat(E2eKafka.recordsOf(TOPIC, a.eventId())).isEmpty();
        assertThat(activityOf(db, orderB)).containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));
        List<ConsumerRecord<String, String>> w6RecordsOfB = E2eKafka.recordsOf(TOPIC, b.eventId());
        assertThat(w6RecordsOfB).hasSize(1);

        int pgCreates = w6Pg.requests().size();
        long pgTransactions = w6Pg.requests().stream().map(r -> FakePaymentGateway.transactionKeyOf(r.orderId())).distinct().count();
        assertThat(pgCreates).isEqualTo(2);
        assertThat(pgTransactions).isEqualTo(2);
        List<Map<String, Object>> activityBBefore = db.activityOfOrder(orderB);
        List<Map<String, Object>> processedBBefore = db.processedOfOrder(orderB);
        log.info("[W7 관찰: 준비] A={}, B={}, B broker={}, PG create C={}, 외부 거래 T={}, PG 조회={}",
            a, b, w6RecordsOfB.stream().map(r -> r.partition() + "@" + r.offset()).toList(), pgCreates, pgTransactions, w6Pg.finds().size());

        // ---- 직접 발행 중단, 같은 DB 에 W7 연결 ----
        E2eContexts.closeQuietly(w6Api);
        db.runScript(Path.of(System.getProperty("e2e.outbox-sql")));
        assertThat(db.tableExists("order_event_outbox")).isTrue();
        assertThat(db.outboxCount()).isZero();
        w7Api = E2eContexts.startApi(TOPIC, List.of("spring.jpa.hibernate.ddl-auto=validate"));
        FakePaymentGateway w7Pg = w7Api.getBean(FakePaymentGateway.class);
        OrderConfirmedBackfill backfill = w7Api.getBean(OrderConfirmedBackfill.class);
        OrderEventRelay relay = w7Api.getBean(OrderEventRelay.class);

        // ---- 복구 ----
        assertThat(backfill.backfill(orderA)).isEqualTo(OrderConfirmedBackfill.Result.RECORDED);
        assertThat(backfill.backfill(orderB)).isEqualTo(OrderConfirmedBackfill.Result.RECORDED);
        ObjectMapper objectMapper = w7Api.getBean(ObjectMapper.class);
        for (Case c : List.of(a, b)) {
            List<Map<String, Object>> outbox = db.outboxOfOrder(c.orderId());
            assertThat(outbox).hasSize(1);
            assertThat(outbox.get(0).get("event_id")).isEqualTo(c.eventId());
            assertThat(outbox.get(0).get("status")).isEqualTo("PENDING");
            JsonNode payload = readTree(objectMapper, (String) outbox.get(0).get("payload"));
            assertThat(payload.get("confirmedAt").asText()).isEqualTo(c.confirmedAt());
            assertThat(itemsOf(payload)).isEqualTo(c.items());
        }

        OrderEventRelay.Run run = relay.runDue();
        assertThat(run.published()).isEqualTo(2);
        E2eKafka.await("A 의 구매 활동 저장", () -> db.processedOfOrder(orderA).size() == 1, () -> "processed(A) = " + db.processedOfOrder(orderA));
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);

        assertThat(activityOf(db, orderA)).containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));
        assertThat(db.processedOfOrder(orderA)).extracting(row -> row.get("event_id")).containsExactly(a.eventId());
        assertThat(db.activityOfOrder(orderB)).as("B 는 재발행이 와도 활동 행·수량이 그대로").isEqualTo(activityBBefore);
        assertThat(db.processedOfOrder(orderB)).isEqualTo(processedBBefore);
        assertThat(E2eKafka.recordsOf(TOPIC, a.eventId())).hasSize(1);
        assertThat(E2eKafka.recordsOf(TOPIC, b.eventId())).hasSize(2);
        assertUntouched(db, a);
        assertUntouched(db, b);
        assertThat(w7Pg.requests()).as("복구 중 PG create 추가 0").isEmpty();
        assertThat(w7Pg.finds()).as("복구 중 PG 조회 추가 0").isEmpty();
        assertThat(db.outboxOfOrder(orderA)).extracting(row -> row.get("status")).containsExactly("PUBLISHED");
        assertThat(db.outboxOfOrder(orderB)).extracting(row -> row.get("status")).containsExactly("PUBLISHED");
        log.info("[W7 관찰: 복구] relay={}, A outbox={}, B outbox={}, A broker={}, B broker={}, A 활동={}, B 활동={}, 복구 PG create={}, 복구 PG 조회={}",
            run, outboxSummary(db, orderA), outboxSummary(db, orderB),
            positions(E2eKafka.recordsOf(TOPIC, a.eventId())), positions(E2eKafka.recordsOf(TOPIC, b.eventId())),
            activityOf(db, orderA), activityOf(db, orderB), w7Pg.requests().size(), w7Pg.finds().size());

        // ---- 같은 대상의 두 번째 복구 ----
        assertThat(backfill.backfill(orderA)).isEqualTo(OrderConfirmedBackfill.Result.ALREADY_RECORDED);
        assertThat(backfill.backfill(orderB)).isEqualTo(OrderConfirmedBackfill.Result.ALREADY_RECORDED);
        assertThat(relay.runDue().claimed()).isZero();
        assertThat(db.outboxCount()).isEqualTo(2);
        assertThat(E2eKafka.recordsOf(TOPIC, a.eventId())).hasSize(1);
        assertThat(E2eKafka.recordsOf(TOPIC, b.eventId())).hasSize(2);
        assertThat(activityOf(db, orderA)).containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));
        assertThat(db.activityOfOrder(orderB)).isEqualTo(activityBBefore);
        assertThat(w7Pg.requests()).isEmpty();
        assertThat(w7Pg.finds()).isEmpty();
    }

    private static ApiTestClient apiClient(ConfigurableApplicationContext context) {
        TestRestTemplate restTemplate = new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + E2eContexts.port(context)));
        return new ApiTestClient(restTemplate, context.getBean(ObjectMapper.class));
    }

    private static Long createOrder(ApiTestClient api, UserModel user, ProductModel productA, ProductModel productB) {
        return api.post("/api/v1/orders", user.getId(), Map.of("items", List.of(
                Map.of("productId", productA.getId(), "quantity", 2),
                Map.of("productId", productB.getId(), "quantity", 1))))
            .assertSuccess(HttpStatus.CREATED).data().path("id").asLong();
    }

    private static void confirm(ApiTestClient api, UserModel user, Long orderId) {
        String status = api.post("/api/v1/orders/" + orderId + "/confirm", user.getId(),
                Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "SAMSUNG", "cardNumber", "1234-5678-9814-1451"))
            .assertSuccess(HttpStatus.OK).data().path("status").asText();
        assertThat(status).isEqualTo("CONFIRMED");
    }

    /** 이번 실행의 확정 사실. confirmedAt·items 는 저장된 주문에서 OrderConfirmed 를 만들어 얻는다 (W6 가 보냈거나 보내려던 값). */
    private static Case observe(ConfigurableApplicationContext context, E2eDb db, Long orderId) {
        OrderModel order = context.getBean(Fixtures.class).reloadOrder(orderId);
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        List<Map<String, Object>> items = event.items().stream()
            .map(item -> Map.<String, Object>of("productId", item.productId(), "quantity", item.quantity()))
            .toList();
        return new Case(orderId, PgOrderId.of(orderId), order.getTransactionKey(), event.eventId(), event.confirmedAt().toString(),
            items, db.order(orderId), db.ledgerOfOrder(orderId));
    }

    /** 복구는 주문 행·재고 원장을 바꾸지 않는다 (재확정·예약 추가 소비 0). */
    private static void assertUntouched(E2eDb db, Case c) {
        assertThat(db.order(c.orderId())).isEqualTo(c.orderRow());
        assertThat(db.ledgerOfOrder(c.orderId())).isEqualTo(c.ledger());
    }

    private static List<Tuple> activityOf(E2eDb db, Long orderId) {
        return db.activityOfOrder(orderId).stream()
            .map(row -> tuple(((Number) row.get("product_id")).longValue(), ((Number) row.get("quantity")).intValue()))
            .toList();
    }

    private static List<Map<String, Object>> itemsOf(JsonNode payload) {
        return StreamSupport.stream(payload.get("items").spliterator(), false)
            .map(item -> Map.<String, Object>of("productId", item.get("productId").asLong(), "quantity", item.get("quantity").asInt()))
            .toList();
    }

    private static JsonNode readTree(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("payload JSON 아님: " + json, e);
        }
    }

    private static String outboxSummary(E2eDb db, Long orderId) {
        return db.outboxOfOrder(orderId).stream()
            .map(row -> row.get("event_id") + " " + row.get("status") + " attempt=" + row.get("attempt_count")
                + " at=" + row.get("published_partition") + "@" + row.get("published_offset"))
            .collect(Collectors.joining(", "));
    }

    private static Set<String> positions(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(r -> r.partition() + "@" + r.offset()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
