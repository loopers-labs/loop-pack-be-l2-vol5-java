package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.facade.OrderOutboxRelay;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.user.entity.User;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.process.BootJarProcess;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 9주차 연결 테스트 (설계 D-193, 실습 §7). 주문 확정 → Outbox relay → Kafka → 실제 streamer 가 구매 활동 저장 →
 * 실제 batch 실행물이 주간 snapshot 을 고정 · 계산 · 공개 → 랭킹 API 까지 한 흐름으로 잇는다.
 * 재시작 · 공개 실패 · 삭제 상품 · 주간 경계는 batch 와 api 의 각 테스트가 맡는다
 */
@SpringBootTest(properties = "order-event.topic=" + WeeklyRankingFlowTest.TOPIC)
@AutoConfigureMockMvc
class WeeklyRankingFlowTest {

    static final String TOPIC = "order-events-ranking-flow";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final Duration STREAMER_START_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(120);
    private static final List<String> STREAMER_TABLES = List.of("processed_order_confirmation", "product_purchase_activity");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderOutboxRelay relay;

    @Autowired
    private OrderConfirmedSender sender;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${commerce-streamer.boot-jar}")
    private String streamerBootJar;

    @Value("${commerce-batch.boot-jar}")
    private String batchBootJar;

    private final TestPgServer pg = TestPgServer.shared();
    private final List<BootJarProcess> processes = new ArrayList<>();
    private BootJarProcess streamer;
    private User user;
    private Product first;
    private Product second;

    @BeforeEach
    void setUp() {
        pg.reset();
        transactionTemplate.executeWithoutResult(status -> {
            user = new User();
            entityManager.persist(user);
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            first = new Product(brand, "첫 번째 상품", 1_000L);
            first.changeStock(20);
            entityManager.persist(first);
            second = new Product(brand, "두 번째 상품", 2_500L);
            second.changeStock(20);
            entityManager.persist(second);
        });
    }

    @AfterEach
    void tearDown() {
        processes.forEach(BootJarProcess::close);
        databaseCleanUp.truncateAllTables();
        // streamer 의 테이블은 이 context 의 엔티티가 아니라 정리 대상에 없음. batch metadata 는 지우지 않음
        STREAMER_TABLES.forEach(table -> jdbc.execute("truncate table `" + table + "`"));
    }

    @DisplayName("실제 주문 확정 이벤트로 만든 구매 활동을 batch 가 주간 snapshot 으로 공개하고, 재발행은 점수를 늘리지 않으며, 늦은 구매는 새 revision 으로만 반영된다.")
    @Test
    void publishesWeeklyRankingFromRealPurchases() throws Exception {
        // arrange: 실제 streamer 를 같은 MySQL · broker 에 띄움
        streamer = start("commerce-streamer", streamerBootJar, streamerArgs());
        streamer.awaitLine(line -> line.contains("Started CommerceStreamerApplication"), STREAMER_START_TIMEOUT);

        // act 1: 한 주문에 두 품목. relay 로 보낸 뒤 같은 record 를 한 번 더 보냄
        Long firstOrder = confirmedOrder(List.of(new Item(first, 2), new Item(second, 1)));
        OrderConfirmedOutbox outbox = deliver(firstOrder);
        sender.send(outbox.getMessageKey(), outbox.getPayload());
        streamer.awaitCount(consumed(outbox.getEventId()), 2, CONSUME_TIMEOUT);
        LocalDate week = weekOf(firstOrder);
        assertThat(runBatch("run", week)).isZero();

        // assert 1: 두 품목이 모두 들어가고 재발행은 점수에 더해지지 않음
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", week.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId(week, 1)))
            .andExpect(jsonPath("$.data.items", hasSize(2)))
            .andExpect(jsonPath("$.data.items[0].productId").value(first.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(2))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].productId").value(second.getId()))
            .andExpect(jsonPath("$.data.items[1].score").value(1));
        assertThat(streamer.count(consumed(outbox.getEventId()).and(line -> line.contains("result=ALREADY_RECORDED ")))).isEqualTo(1);

        // act 2: snapshot 고정 뒤 같은 주의 구매가 도착
        Long lateOrder = confirmedOrder(List.of(new Item(second, 5)));
        OrderConfirmedOutbox late = deliver(lateOrder);
        streamer.awaitCount(consumed(late.getEventId()), 1, CONSUME_TIMEOUT);
        assertThat(weekOf(lateOrder)).as("두 주문이 같은 주에 확정돼야 함").isEqualTo(week);

        // assert 2: 다시 실행하기 전에는 기존 공개본이 그대로이고, 확인 명령이 늦은 구매 1건을 셈
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", week.toString()))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId(week, 1)))
            .andExpect(jsonPath("$.data.items[0].productId").value(first.getId()));
        BootJarProcess check = start("commerce-batch-check", batchBootJar, batchArgs("check", week));
        assertThat(check.awaitExit(BATCH_TIMEOUT)).isZero();
        assertThat(check.count(line -> line.contains("늦은 구매 확인.") && line.contains("lateArrivals=1"))).isEqualTo(1);

        // act 3: 기간만 넘겨 다시 실행하면 새 revision 으로 재계산
        assertThat(runBatch("run", week)).isZero();

        // assert 3: 늦은 구매를 포함한 r2 가 공개되고 r1 은 내려감
        mvc.perform(get("/api/v1/rankings/weekly").param("periodStart", week.toString()))
            .andExpect(jsonPath("$.data.snapshotId").value(snapshotId(week, 2)))
            .andExpect(jsonPath("$.data.items[0].productId").value(second.getId()))
            .andExpect(jsonPath("$.data.items[0].score").value(6))
            .andExpect(jsonPath("$.data.items[1].productId").value(first.getId()))
            .andExpect(jsonPath("$.data.items[1].score").value(2));
        assertThat(jdbc.queryForObject(
            "select status from weekly_ranking_snapshot where snapshot_id = ?", String.class, snapshotId(week, 1))).isEqualTo("COMPLETED");
    }

    private record Item(Product product, int quantity) {}

    private Long confirmedOrder(List<Item> items) throws Exception {
        String body = items.stream()
            .map(item -> "{\"productId\": " + item.product().getId() + ", \"quantity\": " + item.quantity() + "}")
            .reduce((a, b) -> a + ", " + b)
            .map(joined -> "{\"items\": [" + joined + "]}")
            .orElseThrow();
        Long orderId = objectMapper.readTree(postAs("/api/v1/orders", body)).at("/data/id").asLong();
        postAs("/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"attempt-" + orderId + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(orderId));
        assertThat(transactions).hasSize(1);
        pg.complete(transactions.get(0).transactionKey(), "SUCCESS");
        postAs("/api/v1/orders/" + orderId + "/payment/reconcile", "");
        return orderId;
    }

    /** relay 한 회차로 보내고 streamer 가 처음 소비할 때까지 기다림 */
    private OrderConfirmedOutbox deliver(Long orderId) {
        assertThat(relay.runOnce()).isTrue();
        OrderConfirmedOutbox outbox = transactionTemplate.execute(status -> entityManager
            .createQuery("select o from OrderConfirmedOutbox o where o.orderId = :orderId", OrderConfirmedOutbox.class)
            .setParameter("orderId", orderId)
            .getSingleResult());
        streamer.awaitCount(consumed(outbox.getEventId()), 1, CONSUME_TIMEOUT);
        return outbox;
    }

    /** streamer 가 저장한 확정 시각(UTC 벽시계 값)이 속한 UTC 월요일 */
    private LocalDate weekOf(Long orderId) {
        LocalDateTime confirmedAt = jdbc.queryForObject(
            "select confirmed_at from processed_order_confirmation where order_id = ?", LocalDateTime.class, orderId);
        return confirmedAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private int runBatch(String command, LocalDate week) {
        return start("commerce-batch-" + command, batchBootJar, batchArgs(command, week)).awaitExit(BATCH_TIMEOUT);
    }

    private BootJarProcess start(String name, String bootJar, List<String> args) {
        BootJarProcess process = BootJarProcess.start(name, Path.of(bootJar), args);
        processes.add(process);
        return process;
    }

    private String postAs(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header(USER_ID_HEADER, String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
    }

    private static String snapshotId(LocalDate week, int revision) {
        return "weekly-" + week.toString().replace("-", "") + "-r" + revision;
    }

    private static Predicate<String> consumed(String eventId) {
        return line -> line.contains("주문 확정 이벤트 소비") && line.contains("eventId=" + eventId + " ");
    }

    private List<String> batchArgs(String command, LocalDate week) {
        List<String> args = new ArrayList<>(commonArgs());
        args.addAll(List.of(
            "--job.name=weeklyRanking",
            "--spring.batch.job.enabled=false",
            "--weekly-ranking.command=" + command,
            "--periodStart=" + week
        ));
        return args;
    }

    private List<String> streamerArgs() {
        List<String> args = new ArrayList<>(commonArgs());
        args.addAll(List.of(
            "--server.port=0",
            "--spring.kafka.bootstrap-servers=" + System.getProperty("spring.kafka.bootstrap-servers"),
            "--order-event.topic=" + TOPIC,
            "--order-event.consumer-group=purchase-activity-ranking-flow"
        ));
        return args;
    }

    private List<String> commonArgs() {
        return List.of(
            "--spring.profiles.active=test",
            "--management.server.port=0",
            // 같은 DB 의 api 테이블을 지우지 않도록 create 대신 update
            "--spring.jpa.hibernate.ddl-auto=update",
            "--datasource.mysql-jpa.main.jdbc-url=" + System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            "--datasource.mysql-jpa.main.username=" + System.getProperty("datasource.mysql-jpa.main.username"),
            "--datasource.mysql-jpa.main.password=" + System.getProperty("datasource.mysql-jpa.main.password"),
            "--datasource.redis.database=0",
            "--datasource.redis.master.host=" + System.getProperty("datasource.redis.master.host"),
            "--datasource.redis.master.port=" + System.getProperty("datasource.redis.master.port"),
            "--datasource.redis.replicas[0].host=" + System.getProperty("datasource.redis.replicas[0].host"),
            "--datasource.redis.replicas[0].port=" + System.getProperty("datasource.redis.replicas[0].port")
        );
    }
}
