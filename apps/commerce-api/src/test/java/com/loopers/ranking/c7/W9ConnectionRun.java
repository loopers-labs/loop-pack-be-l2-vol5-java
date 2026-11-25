package com.loopers.ranking.c7;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W9 §7 한 연결 실행 — docs/week9/c7/run.sh가 막마다 새 JVM으로 부른다 (W7 C-6과 같은 방식, D-9).
 * 실제 주문 확정(PG) → Outbox relay → 실제 Kafka 3대 → commerce-streamer(java -jar) → 구매 활동
 * → commerce-batch(java -jar, D-8 명령 그대로) → 이 앱의 랭킹 API.
 * 기대값의 주(periodStart)는 이 실행이 만든 주문의 확정 시각(UTC)으로 계산한다. 막 사이 상태는 W9_FACTS(JSON)로 넘긴다.
 */
@Tag("isolated")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"management.server.port=0", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class W9ConnectionRun {

    private static final int PORT = freePort();
    private static final String TOPIC = "order-events";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("datasource.mysql-jpa.main.jdbc-url", () -> System.getenv("W9_JDBC_URL"));
        registry.add("spring.kafka.bootstrap-servers", () -> System.getenv("W9_BOOTSTRAP"));
        registry.add("pg.local.base-url", () -> System.getenv("W9_PG_URL"));
        registry.add("pg.local.callback-url", () -> "http://127.0.0.1:" + PORT + "/api/v1/payments/callback");
        // 1막만 테이블을 만든다(update). 이후 막은 그대로 쓴다
        registry.add("spring.jpa.hibernate.ddl-auto", () -> System.getenv().getOrDefault("W9_DDL_AUTO", "none"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 1막 — 발제 fixture와 같은 구매를 실제 주문으로 만든다: A(7×2, 8×1) · B(7×3) · C(9×5). relay로 Kafka에 보내고 streamer가 저장하기를 기다린다.
     */
    @Test
    void act1_confirmOrdersAndRelay() throws Exception {
        // given: 상품 ID를 7·8·9로 맞춘다
        jdbc.execute("ALTER TABLE products AUTO_INCREMENT = 7");
        UserModel user = userJpaRepository.save(new UserModel("W9 고객"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("W9 브랜드", null));
        ProductModel p7 = productJpaRepository.save(new ProductModel(brand.getId(), "W9 상품 7", 9_000, 100));
        ProductModel p8 = productJpaRepository.save(new ProductModel(brand.getId(), "W9 상품 8", 12_000, 100));
        ProductModel p9 = productJpaRepository.save(new ProductModel(brand.getId(), "W9 상품 9", 30_000, 100));
        assertThat(List.of(p7.getId(), p8.getId(), p9.getId())).containsExactly(7L, 8L, 9L);

        // when
        long orderA = createAndConfirm(user, "w9-a", Map.of(7L, 2, 8L, 1));
        long orderB = createAndConfirm(user, "w9-b", Map.of(7L, 3));
        long orderC = createAndConfirm(user, "w9-c", Map.of(9L, 5));
        relayAll();

        // then: streamer가 구매 활동 4행을 저장했다
        await().atMost(Duration.ofSeconds(60)).until(() -> activityCount() == 4);
        LocalDateTime firstConfirmed = jdbc.queryForObject("SELECT MIN(confirmed_at) FROM product_purchase_activity", LocalDateTime.class);
        LocalDateTime lastConfirmed = jdbc.queryForObject("SELECT MAX(confirmed_at) FROM product_purchase_activity", LocalDateTime.class);
        LocalDate periodStart = mondayOf(firstConfirmed);
        assertThat(mondayOf(lastConfirmed)).as("세 주문이 같은 UTC 주에 확정돼야 한다").isEqualTo(periodStart);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("periodStart", periodStart.toString());
        facts.put("userId", user.getId());
        facts.put("orders", Map.of("A", orderA, "B", orderB, "C", orderC));
        facts.put("eventIdA", jdbc.queryForObject("SELECT event_id FROM order_outbox WHERE order_id = ?", String.class, orderA));
        facts.put("activity", activity());
        writeJson(System.getenv("W9_FACTS"), facts);
        System.out.println("[W9 1막] " + objectMapper.writeValueAsString(facts));
    }

    /**
     * 2막 — batch(r1)가 공개한 결과를 랭킹 API로 본다. 기대: 7 = 5점·1위, 9 = 5점·2위, 8 = 1점·3위.
     */
    @Test
    void act2_rankingApiShowsR1() throws Exception {
        // given
        Map<String, Object> facts = readFacts();
        String periodStart = (String) facts.get("periodStart");

        // when
        String body = getRanking(periodStart);

        // then
        assertThat(JsonPath.parse(body).read("$.data.status", String.class)).isEqualTo("PUBLISHED");
        assertThat(JsonPath.parse(body).read("$.data.snapshotId", String.class)).isEqualTo(snapshotId(periodStart, 1));
        assertThat(ranked(body)).containsExactly("7:5:1", "9:5:2", "8:1:3");
        writeJson(System.getenv("W9_OUT") + "/act2-ranking-r1.json", objectMapper.readValue(body, Map.class));
    }

    /**
     * 3막 — ① 주문 A의 같은 이벤트를 Kafka에 다시 보낸다 (W7 재발행과 같은 사건) → 구매 활동은 그대로여야 한다.
     * ② 같은 주에 늦은 구매 D(8×10)가 확정된다 → 원본에는 들어오지만 r1 snapshot은 그대로여야 한다.
     */
    @Test
    void act3_republishSameEventAndLatePurchase() throws Exception {
        // given
        Map<String, Object> facts = readFacts();
        String eventIdA = (String) facts.get("eventIdA");
        Map<String, Object> outbox = jdbc.queryForMap("SELECT message_key, payload FROM order_outbox WHERE event_id = ?", eventIdA);

        // when ①: 같은 key·payload를 다시 보낸다
        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(TOPIC, (String) outbox.get("message_key"), (String) outbox.get("payload"))).get();
        }

        // then ①: streamer가 같은 사실의 재전달로 처리했고, 구매 활동은 4행 그대로다
        await().atMost(Duration.ofSeconds(60)).until(() -> streamerLogHasLine("no-op", "eventId=" + eventIdA));
        assertThat(activityCount()).isEqualTo(4);

        // when ②: 늦은 구매
        UserModel user = userJpaRepository.findById(((Number) facts.get("userId")).longValue()).orElseThrow();
        long orderD = createAndConfirm(user, "w9-d", Map.of(8L, 10));
        relayAll();

        // then ②: 원본은 5행, 같은 주
        await().atMost(Duration.ofSeconds(60)).until(() -> activityCount() == 5);
        LocalDateTime lateConfirmed = jdbc.queryForObject("SELECT confirmed_at FROM product_purchase_activity WHERE order_id = ?", LocalDateTime.class, orderD);
        assertThat(mondayOf(lateConfirmed).toString()).isEqualTo(facts.get("periodStart"));
        facts.put("orderD", orderD);
        writeJson(System.getenv("W9_FACTS"), facts);
    }

    /**
     * 4막 — 늦은 구매 뒤에도 (같은 명령을 다시 쳐도) r1 입력과 공개본은 그대로다. 재계산은 사람이 명시해야 한다.
     */
    @Test
    void act4_lateArrivalDoesNotChangeR1() throws Exception {
        // given
        String periodStart = (String) readFacts().get("periodStart");

        // when
        String body = getRanking(periodStart);

        // then
        assertThat(JsonPath.parse(body).read("$.data.snapshotId", String.class)).isEqualTo(snapshotId(periodStart, 1));
        assertThat(ranked(body)).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(jdbc.queryForMap("SELECT input_count, input_quantity_sum FROM ranking_snapshot WHERE snapshot_id = ?", snapshotId(periodStart, 1)))
            .containsEntry("input_count", 4L).containsEntry("input_quantity_sum", 11L);
        writeJson(System.getenv("W9_OUT") + "/act4-ranking-still-r1.json", objectMapper.readValue(body, Map.class));
    }

    /**
     * 5막 — --recompute로 r2가 공개된 뒤: 8 = 11점·1위, 7 = 5점·2위, 9 = 5점·3위. 재발행된 이벤트는 두 번 세지 않았다(7 = 5점).
     */
    @Test
    void act5_rankingApiShowsR2() throws Exception {
        // given
        String periodStart = (String) readFacts().get("periodStart");

        // when
        String body = getRanking(periodStart);

        // then
        assertThat(JsonPath.parse(body).read("$.data.snapshotId", String.class)).isEqualTo(snapshotId(periodStart, 2));
        assertThat(ranked(body)).containsExactly("8:11:1", "7:5:2", "9:5:3");
        assertThat(jdbc.queryForMap("SELECT input_count, input_quantity_sum FROM ranking_snapshot WHERE snapshot_id = ?", snapshotId(periodStart, 1)))
            .containsEntry("input_count", 4L).containsEntry("input_quantity_sum", 11L);
        writeJson(System.getenv("W9_OUT") + "/act5-ranking-r2.json", objectMapper.readValue(body, Map.class));
    }

    private long createAndConfirm(UserModel user, String idempotencyKey, Map<Long, Integer> items) throws Exception {
        String itemsJson = items.entrySet().stream()
            .map(e -> "{\"productId\": " + e.getKey() + ", \"quantity\": " + e.getValue() + "}")
            .reduce((a, b) -> a + ", " + b).orElseThrow();
        String created = mockMvc.perform(post("/api/v1/orders").header("X-USER-ID", user.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\": [" + itemsJson + "]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long orderId = JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId).header("X-USER-ID", user.getId())
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}"))
            .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).until(() ->
            "CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)));
        return orderId;
    }

    private void relayAll() {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
            relay.relayOnce();
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE status <> 'PUBLISHED'", Integer.class);
            return pending != null && pending == 0;
        });
    }

    private String getRanking(String periodStart) throws Exception {
        return mockMvc.perform(get("/api/v1/rankings/weekly").param("periodStart", periodStart))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** "상품:점수:순위" 목록 */
    private static List<String> ranked(String body) {
        List<Map<String, Object>> items = JsonPath.parse(body).read("$.data.items");
        return items.stream().map(item -> item.get("productId") + ":" + item.get("score") + ":" + item.get("rank")).toList();
    }

    private long activityCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> activity() {
        return jdbc.queryForList("SELECT order_id AS orderId, product_id AS productId, quantity, CAST(confirmed_at AS CHAR) AS confirmedAt FROM product_purchase_activity ORDER BY order_id, product_id");
    }

    private static LocalDate mondayOf(LocalDateTime utc) {
        return utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String snapshotId(String periodStart, long revision) {
        return "weekly-" + LocalDate.parse(periodStart).format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    /** streamer 로그에 두 글자가 함께 있는 줄이 있나 — 예: "같은 사실의 재전달 — 변경 없이 완료(no-op). eventId=…" */
    private static boolean streamerLogHasLine(String first, String second) throws IOException {
        return Files.readAllLines(Path.of(System.getenv("W9_STREAMER_LOG"))).stream()
            .anyMatch(line -> line.contains(first) && line.contains(second));
    }

    private static KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("W9_BOOTSTRAP"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFacts() throws IOException {
        return objectMapper.readValue(Files.readString(Path.of(System.getenv("W9_FACTS"))), Map.class);
    }

    private void writeJson(String path, Object value) throws IOException {
        Files.writeString(Path.of(path), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
