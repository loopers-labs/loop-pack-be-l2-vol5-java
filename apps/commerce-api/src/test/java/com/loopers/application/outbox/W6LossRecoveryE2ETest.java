package com.loopers.application.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.support.pg.TestPg;
import com.loopers.support.process.BootJarProcess;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 6주차 버전(ad7f2c8) 서버로 "확정됐는데 안 나간 주문 A"와 "이미 나가 소비된 주문 B"를 만들고, 7주차 복구(OutboxBackfill) → relay →
 * consumer로 A만 채워지고 B는 늘지 않는지 본다(docs/week7/decisions.md D-5, D-10, D-13, D-14, INV-706).
 *
 * <p>check에서 뺀다 — {@code @Tag("w6-recovery")}, {@code ./gradlew :apps:commerce-api:w6RecoveryTest}로 따로 돌린다(D-14).
 * 이 작업은 자기 Gradle test JVM을 쓰므로, 이 JVM의 MySQL·Kafka·Redis 컨테이너와 테스트 PG(WireMock)는 이 테스트만 쓴다.
 *
 * <p>준비(6주차 그대로, 실행 설정만 바꿈 — D-10):
 * 1. 6주차 api jar 1회차: Kafka 주소를 아무도 없는 곳으로 주고 띄운다. 실제 HTTP로 주문 A → 결제 시작 → 같은 거래 조회 SUCCESS로
 *    확정한다. send가 max.block(5초) 뒤 실패해 "미발행" 로그만 남는다. 끈다.
 * 2. 2회차: 진짜 Kafka 주소로 다시 띄워 주문 B를 같은 길로 확정한다. B의 record가 topic에 실린다. 끈다.
 * 3. 현재 streamer jar를 띄워 B를 소비시킨다. A는 활동 0, 둘 다 Outbox 행 없음(6주차 스키마에는 테이블도 없다).
 *
 * <p>복구(7주차, 같은 DB·Kafka·PG를 그대로 이어서): OutboxBackfill(A·B) → relay → consumer → 같은 복구 한 번 더 → PG 호출 증가 0.
 *
 * <p>스키마 순서 — "처음 스키마를 만지는 서버만 create"(D-10 "바로잡은 것"):
 * - 6주차 1회차가 처음이라 create, 그 뒤 6주차 2회차·streamer·7주차 연결은 모두 update다. 6주차 테이블을 6주차 코드가 만들고, 7주차는
 *   운영 배포처럼 그 위에 자기 테이블(outbox_event)만 더한다.
 * - 그래서 7주차 Spring 컨텍스트를 테스트 클래스에 {@code @SpringBootTest}로 붙이지 않는다. 붙이면 테스트 메서드보다 먼저 떠서 스키마를
 *   처음 만지게 되고, 테스트 실행 리스너(TestPgResetListener·RedisCacheCleanUpListener)가 준비와 복구 사이에 PG·Redis를 비울 수 있다.
 *   대신 같은 {@code @SpringBootTest} 설정을 단 {@link W7Context}를 준비가 끝난 뒤 {@link TestContextManager}로 직접 읽는다 — 테스트
 *   설정 제외 규칙과 컨테이너·PG initializer는 그대로 쓰고, 실행 리스너는 부르지 않는다. 비우기·truncate는 어디에도 없다.
 *
 * <p>과제 조건: SUCCESS·CONFIRMED를 SQL로 넣지 않는다(상태는 6주차 HTTP 경로가 만든다), Outbox 행을 지우지 않는다, 제품 코드 우회
 * 스위치가 없다, 준비와 복구 사이에 DB·broker·PG를 초기화하지 않는다, 복구는 PG를 부르지 않는다. 기다림은 모두 상한이 있고, 실패하면
 * DB 행·topic record·프로세스 로그 끝부분을 출력한다. jar 경로가 없으면 건너뛰지 않고 실패한다 — skip은 통과가 아니다.
 *
 * <p>사용자·브랜드·상품은 JDBC로 넣는다. 6주차 HTTP API에는 사용자 생성이 없고, 관리자 API는 인증 수단이 없어 실제 HTTP로 부를 수
 * 없다(6주차 AcceptanceTestSecurityConfig 설명). 넣는 것은 참조 데이터뿐이고 주문·결제 상태는 넣지 않는다.
 */
@Tag("w6-recovery")
class W6LossRecoveryE2ETest {

    private static final String TOPIC = "order-events-w6-recovery-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-w6-recovery-" + UUID.randomUUID();
    private static final Duration PROCESS_READY = Duration.ofMinutes(3);
    private static final Duration WAIT = Duration.ofSeconds(60);
    /** 6주차 send가 max.block(5초) 뒤 실패해 미발행 로그를 남길 때까지. */
    private static final Duration UNPUBLISHED_WAIT = Duration.ofSeconds(30);
    private static final Pattern API_READY = Pattern.compile("Started CommerceApiApplication");
    private static final Pattern STREAMER_READY = Pattern.compile("Started CommerceStreamerApplication");
    private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final int INITIAL_STOCK = 10;
    private static final long PRICE = 1_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> STREAMER_TABLES = List.of(
        "processed_order_confirmation", "product_purchase_activity", "purchase_activity_conflict", "order_confirmed_contract_violation");

    /**
     * 7주차 연결. 테스트 클래스가 아니다(테스트 메서드 없음) — 준비가 끝난 뒤 {@link TestContextManager}로 직접 읽는다.
     * 처음 스키마를 만진 6주차 서버 뒤에 오므로 update다(D-10).
     */
    @SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=update")
    static class W7Context {

        @DynamicPropertySource
        static void topic(DynamicPropertyRegistry registry) {
            registry.add("events.order-confirmed.topic", () -> TOPIC);
        }
    }

    @DisplayName("6주차 서버로 확정됐지만 안 나간 주문 A와 이미 소비된 주문 B를 만든 뒤, 7주차 복구가 저장된 사실로 두 행을 만들고 relay가 보내면 "
        + "consumer는 A의 구매 활동만 새로 만들고 B는 그대로 둔다. 같은 복구를 다시 돌리면 아무것도 쓰지 않고, 복구 동안 PG 호출·재확정·"
        + "예약 소비는 늘지 않는다. (D-5, D-10, D-13, D-14, INV-706)")
    @Test
    void lostW6Event_isRecoveredFromStoredFacts_withoutDuplicatingTheDeliveredOne() throws Exception {
        Path w6Jar = requiredJar("w6.apiJar", ":apps:commerce-api:prepareW6ApiJar");
        Path streamerJar = requiredJar("streamer.bootJar", ":apps:commerce-streamer:bootJar");
        Run run = new Run(w6Jar, streamerJar);
        try {
            run.prepare();
            run.recover();
        } catch (Throwable failure) {
            run.printState();
            throw failure;
        } finally {
            run.close();
        }
    }

    private static Path requiredJar(String property, String producer) {
        String value = System.getProperty(property);
        assertThat(value)
            .as("실행 jar 경로(시스템 프로퍼티 %s) — Gradle :apps:commerce-api:w6RecoveryTest가 %s 뒤에 넣는다. "
                + "IDE에서 돌릴 때는 그 작업 뒤 -D%s=<jar>를 준다", property, producer, property)
            .isNotBlank();
        Path jar = Path.of(value);
        assertThat(Files.isRegularFile(jar)).as("실행 jar 파일 %s", jar).isTrue();
        return jar;
    }

    // ---------------------------------------------------------------- 한 번의 실행

    /** 저장된 주문 사실. 복구 전후를 같은 모양으로 비교한다. */
    private record StoredOrder(long orderId, String status, String confirmedAt, Map<Long, Integer> items,
                               String attemptStatus, String transactionKey, Map<Long, Integer> stocks) {

        String eventId() {
            return "order-confirmed:v1:" + orderId;
        }
    }

    private static final class Run {

        private final Path w6Jar;
        private final Path streamerJar;
        private final List<BootJarProcess> processes = new ArrayList<>();
        private JdbcTemplate jdbc;
        private WireMockServer pg;
        private BootJarProcess streamer;
        private TestContextManager w7;
        private long userId;
        private Map<Long, Integer> itemsA;
        private Map<Long, Integer> itemsB;
        private Long orderA;
        private Long orderB;

        Run(Path w6Jar, Path streamerJar) {
            this.w6Jar = w6Jar;
            this.streamerJar = streamerJar;
        }

        // ------------------------------------------------------------ 준비 (6주차)

        void prepare() throws Exception {
            // 1. 이 JVM의 픽스처를 띄운다. 공용 픽스처는 static 블록·생성자에서 컨테이너를 띄우고 접속 정보를 시스템 프로퍼티로 넣는다
            new MySqlTestContainersConfig();
            new RedisTestContainersConfig();
            KafkaTestContainers.createTopic(TOPIC);
            TestPg.reset();
            pg = TestPg.server();
            jdbc = new JdbcTemplate(new DriverManagerDataSource(required("datasource.mysql-jpa.main.jdbc-url"),
                required("datasource.mysql-jpa.main.username"), required("datasource.mysql-jpa.main.password")));

            // 2. 6주차 1회차 — 처음 스키마를 만지므로 create. Kafka는 아무도 없는 주소
            BootJarProcess first = startW6("commerce-api-w6-run1", "create", unusedLocalAddress());
            int firstPort = port(first);
            seedReferenceData();
            orderA = placeAndConfirm(firstPort, itemsA, "A");
            first.awaitLog(Pattern.compile("주문 확정 이벤트 미발행\\([^)]*\\): orderId=" + orderA + ", eventId="
                + Pattern.quote("order-confirmed:v1:" + orderA)), UNPUBLISHED_WAIT);
            StoredOrder a = stored(orderA);
            System.out.println("[W6 재현 1회차] A=" + a);
            assertConfirmedAndConsumed(a, itemsA);
            first.close();

            // 3. 6주차 2회차 — update, 진짜 Kafka 주소. B는 실제로 발행된다
            BootJarProcess second = startW6("commerce-api-w6-run2", "update", KafkaTestContainers.bootstrapServers());
            orderB = placeAndConfirm(port(second), itemsB, "B");
            ConsumerRecord<byte[], byte[]> recordB = awaitSingleRecord("order-confirmed:v1:" + orderB);
            StoredOrder b = stored(orderB);
            System.out.println("[W6 재현 2회차] B=" + b + " / record " + recordB.partition() + "@" + recordB.offset());
            assertConfirmedAndConsumed(b, itemsB);
            assertThat(stored(orderA)).as("2회차가 A를 지우거나 바꾸지 않았다(update)").isEqualTo(a);
            second.close();

            // 4. streamer(현재 코드) — update로 자기 테이블만 더하고 B를 소비한다
            streamer = startStreamer();
            TopicPartition partitionB = new TopicPartition(TOPIC, recordB.partition());
            awaitCommittedPast(partitionB, recordB.offset());
            List<Map<String, Object>> activityB = awaitActivity(orderB, itemsB);
            assertAll(
                () -> assertThat(activityRows(orderA)).as("A의 구매 활동").isEmpty(),
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ?", orderA)).as("A 처리 기록").isZero(),
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ?", orderB)).as("B 처리 기록").isEqualTo(1),
                () -> assertThat(stored(orderA)).isEqualTo(a),
                () -> assertThat(stored(orderB)).isEqualTo(b),
                () -> assertThat(outboxRowsOf(orderA, orderB)).as("A·B의 Outbox 행(6주차 스키마에는 테이블도 없다)").isZero(),
                () -> assertThat(recordsWithEventId(a.eventId())).as("A의 record").isZero(),
                () -> assertThat(recordsWithEventId(b.eventId())).as("B의 record").isEqualTo(1)
            );

            // 5. 이번 실행의 사실과 PG 호출 수를 남긴다(PR용)
            List<String> transactions = pgCreateTransactions();
            String summary = "\n===== W6 누락 재현 요약 (docs/week7/decisions.md D-10, D-14) =====\n"
                + "topic=" + TOPIC + ", consumer group=" + GROUP + "\n"
                + "outbox_event 테이블(6주차 스키마): " + (tableExists("outbox_event") ? "있음" : "없음") + "\n"
                + describe("A(send 실패, 미발행)", a) + "\n"
                + describe("B(발행·소비 완료)", b) + "\n"
                + "B 구매 활동: " + activityB + "\n"
                + "PG 거래 생성 요청 C=" + transactions.size() + ", 서로 다른 외부 거래 T=" + new LinkedHashSet<>(transactions).size()
                + " " + transactions + "\n"
                + "PG 요청 전체(생성+조회)=" + pg.getAllServeEvents().size() + "\n"
                + "===== 끝 =====";
            System.out.println(summary);
            assertThat(transactions).as("PG 거래 생성 C").hasSize(2);
            assertThat(new LinkedHashSet<>(transactions)).as("외부 거래 T").hasSize(2).contains(a.transactionKey(), b.transactionKey());
        }

        // ------------------------------------------------------------ 복구 (7주차)

        void recover() throws Exception {
            StoredOrder a = stored(orderA);
            StoredOrder b = stored(orderB);
            List<Map<String, Object>> activityB = activityRows(orderB);
            int pgEventsBefore = pg.getAllServeEvents().size();
            List<String> transactionsBefore = pgCreateTransactions();

            // 6. 7주차 연결(update) → 저장된 사실로 복구 → relay
            w7 = new TestContextManager(W7Context.class);
            ApplicationContext context = w7.getTestContext().getApplicationContext();
            OutboxBackfill backfill = context.getBean(OutboxBackfill.class);
            OutboxRelay relay = context.getBean(OutboxRelay.class);
            OutboxEventRepository outbox = context.getBean(OutboxEventRepository.class);
            assertThat(outboxRowsOf(orderA, orderB)).as("복구 전 A·B의 Outbox 행").isZero();
            assertThat(count("select count(*) from outbox_event")).as("복구 전 Outbox 행 전체").isZero();

            OutboxBackfill.Result resultA = backfill.backfill(orderA);
            OutboxBackfill.Result resultB = backfill.backfill(orderB);
            assertThat(resultA).isEqualTo(OutboxBackfill.Result.CREATED);
            assertThat(resultB).isEqualTo(OutboxBackfill.Result.CREATED);
            OutboxEvent rowA = singleRow(outbox, orderA);
            OutboxEvent rowB = singleRow(outbox, orderB);
            assertRowBuiltFromStoredFacts(rowA, a);
            assertRowBuiltFromStoredFacts(rowB, b);
            // B는 6주차가 보낸 record와 글자까지 같은 payload다 — consumer는 conflict가 아니라 중복으로 받는다(D-5)
            assertThat(rowB.getPayload().getBytes(StandardCharsets.UTF_8))
                .as("B의 복구 payload와 6주차 record value").isEqualTo(recordsOf(b.eventId()).get(0).value());

            List<OutboxRelayRound> rounds = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!(published(outbox, orderA) && published(outbox, orderB)) && System.nanoTime() < deadline) {
                rounds.add(relay.runOnce());
                Thread.sleep(200);
            }
            System.out.println("[복구 6 relay] rounds=" + rounds + " / A=" + describe(singleRow(outbox, orderA))
                + " / B=" + describe(singleRow(outbox, orderB)));
            assertThat(published(outbox, orderA) && published(outbox, orderB)).as("A·B 행이 PUBLISHED").isTrue();

            // 7. consumer — A는 새로 생기고, B는 그대로다. topic에는 B가 두 번(6주차·7주차), A가 한 번
            assertThat(recordsWithEventId(a.eventId())).as("A의 record").isEqualTo(1);
            assertThat(recordsWithEventId(b.eventId())).as("B의 record(6주차 + 7주차)").isEqualTo(2);
            awaitAllCommitted();
            List<Map<String, Object>> activityA = awaitActivity(orderA, itemsA);
            System.out.println("[복구 7 consumer] A 구매 활동=" + activityA + " / B 구매 활동=" + activityRows(orderB));
            assertAll(
                () -> assertThat(activityRows(orderB)).as("B의 구매 활동(행 id·수량 그대로)").isEqualTo(activityB),
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ? and event_id = ?",
                    orderA, a.eventId())).as("A 처리 기록").isEqualTo(1),
                () -> assertThat(count("select count(*) from processed_order_confirmation where order_id = ?", orderB)).as("B 처리 기록").isEqualTo(1),
                () -> assertThat(count("select count(*) from purchase_activity_conflict where order_id in (?, ?) or event_id in (?, ?)",
                    orderA, orderB, a.eventId(), b.eventId())).as("A·B conflict 기록").isZero(),
                () -> assertThat(count("select count(*) from purchase_activity_conflict where record_topic = ?", TOPIC)).as("이 topic의 conflict").isZero(),
                () -> assertThat(count("select count(*) from order_confirmed_contract_violation where record_topic = ?", TOPIC))
                    .as("이 topic의 계약 깨짐").isZero()
            );

            // 8. 같은 대상을 한 번 더 — 같은 행이 있어 아무것도 쓰지 않는다
            OutboxBackfill.Result againA = backfill.backfill(orderA);
            OutboxBackfill.Result againB = backfill.backfill(orderB);
            OutboxRelayRound idle = relay.runOnce();
            System.out.println("[복구 8 두 번째] A=" + againA + ", B=" + againB + ", relay=" + idle);
            assertAll(
                () -> assertThat(againA).isEqualTo(OutboxBackfill.Result.ALREADY_PRESENT_SAME),
                () -> assertThat(againB).isEqualTo(OutboxBackfill.Result.ALREADY_PRESENT_SAME),
                () -> assertThat(outboxRowsOf(orderA, orderB)).as("A·B의 Outbox 행").isEqualTo(2),
                () -> assertThat(count("select count(*) from outbox_event")).as("Outbox 행 전체").isEqualTo(2),
                () -> assertThat(idle.due()).as("두 번째 복구 뒤 보낼 행").isZero(),
                () -> assertThat(singleRow(outbox, orderA).getPayload()).isEqualTo(rowA.getPayload()),
                () -> assertThat(singleRow(outbox, orderB).getPayload()).isEqualTo(rowB.getPayload()),
                () -> assertThat(recordsWithEventId(a.eventId())).isEqualTo(1),
                () -> assertThat(recordsWithEventId(b.eventId())).isEqualTo(2),
                () -> assertThat(activityRows(orderA)).isEqualTo(activityA),
                () -> assertThat(activityRows(orderB)).isEqualTo(activityB)
            );

            // 9. 복구는 보내는 쪽 일만 했다 — PG 호출 0, 재확정 없음, 예약 소비 그대로
            List<String> transactionsAfter = pgCreateTransactions();
            int pgEventsAfter = pg.getAllServeEvents().size();
            String summary = "\n===== 7주차 복구 요약 (docs/week7/decisions.md D-5, D-14) =====\n"
                + "backfill 1회차 A=" + resultA + ", B=" + resultB + " / 2회차 A=" + againA + ", B=" + againB + "\n"
                + "A 행: " + describe(singleRow(outbox, orderA)) + "\n"
                + "B 행: " + describe(singleRow(outbox, orderB)) + "\n"
                + "topic record 수: A=" + recordsWithEventId(a.eventId()) + ", B=" + recordsWithEventId(b.eventId()) + "\n"
                + "A 구매 활동: " + activityA + "\n"
                + "PG 거래 생성 C=" + transactionsAfter.size() + ", 외부 거래 T=" + new LinkedHashSet<>(transactionsAfter).size()
                + ", 복구 시작 뒤 PG 요청 증가=" + (pgEventsAfter - pgEventsBefore) + "\n"
                + "===== 끝 =====";
            System.out.println(summary);
            assertAll(
                () -> assertThat(pgEventsAfter - pgEventsBefore).as("복구 시작 뒤 PG 요청(생성·조회·기타) 증가").isZero(),
                () -> assertThat(transactionsAfter).as("PG 거래 생성 C").isEqualTo(transactionsBefore),
                () -> assertThat(new LinkedHashSet<>(transactionsAfter)).as("외부 거래 T").hasSize(2),
                () -> assertThat(stored(orderA)).as("A — 재확정·예약 소비 없음(confirmed_at·재고·결제 시도 그대로)").isEqualTo(a),
                () -> assertThat(stored(orderB)).as("B — 재확정·예약 소비 없음").isEqualTo(b),
                () -> assertThat(streamer.isAlive()).as("streamer 프로세스").isTrue()
            );
        }

        // ------------------------------------------------------------ 6주차 서버와 HTTP

        private BootJarProcess startW6(String name, String ddlAuto, String bootstrapServers) {
            BootJarProcess process = BootJarProcess.builder(name, w6Jar)
                .jvmArg("-Duser.timezone=" + System.getProperty("user.timezone"))
                .jvmArg("-Dstdout.encoding=UTF-8")
                .jvmArg("-Dstderr.encoding=UTF-8")
                .arg("spring.profiles.active", "test")
                .arg("server.port", "0")
                // 관리 포트는 끈다 — 실제 포트 줄이 하나만 나오게 한다(6주차 코드는 actuator를 쓰지 않는다)
                .arg("management.server.port", "-1")
                .arg("datasource.mysql-jpa.main.jdbc-url", required("datasource.mysql-jpa.main.jdbc-url"))
                .arg("datasource.mysql-jpa.main.username", required("datasource.mysql-jpa.main.username"))
                .arg("datasource.mysql-jpa.main.password", required("datasource.mysql-jpa.main.password"))
                .arg("spring.jpa.hibernate.ddl-auto", ddlAuto)
                .arg("spring.kafka.bootstrap-servers", bootstrapServers)
                .arg("spring.kafka.admin.properties.bootstrap.servers", bootstrapServers)
                .arg("datasource.redis.database", "0")
                .arg("datasource.redis.master.host", required("datasource.redis.master.host"))
                .arg("datasource.redis.master.port", required("datasource.redis.master.port"))
                .arg("datasource.redis.replicas[0].host", required("datasource.redis.replicas[0].host"))
                .arg("datasource.redis.replicas[0].port", required("datasource.redis.replicas[0].port"))
                .arg("pg.base-url", TestPg.baseUrl())
                // 백그라운드 회복 작업이 PG 조회를 더하지 않게 끈다 — 확정은 테스트가 HTTP reconcile로 한다
                .arg("payment.recovery.enabled", "false")
                .arg("events.order-confirmed.topic", TOPIC)
                .readyWhen(API_READY, PROCESS_READY)
                .start();
            processes.add(process);
            return process;
        }

        private BootJarProcess startStreamer() {
            String bootstrapServers = KafkaTestContainers.bootstrapServers();
            BootJarProcess process = BootJarProcess.builder("commerce-streamer-w6-recovery", streamerJar)
                .jvmArg("-Duser.timezone=" + System.getProperty("user.timezone"))
                .jvmArg("-Dstdout.encoding=UTF-8")
                .jvmArg("-Dstderr.encoding=UTF-8")
                .arg("spring.profiles.active", "test")
                .arg("server.port", "0")
                .arg("management.server.port", "0")
                .arg("datasource.mysql-jpa.main.jdbc-url", required("datasource.mysql-jpa.main.jdbc-url"))
                .arg("datasource.mysql-jpa.main.username", required("datasource.mysql-jpa.main.username"))
                .arg("datasource.mysql-jpa.main.password", required("datasource.mysql-jpa.main.password"))
                // 6주차가 만든 스키마를 지우지 않는다 — 자기 테이블만 더한다(D-10)
                .arg("spring.jpa.hibernate.ddl-auto", "update")
                .arg("spring.kafka.bootstrap-servers", bootstrapServers)
                .arg("spring.kafka.admin.properties.bootstrap.servers", bootstrapServers)
                .arg("datasource.redis.master.host", required("datasource.redis.master.host"))
                .arg("datasource.redis.master.port", required("datasource.redis.master.port"))
                .arg("datasource.redis.replicas[0].host", required("datasource.redis.replicas[0].host"))
                .arg("datasource.redis.replicas[0].port", required("datasource.redis.replicas[0].port"))
                .arg("events.order-confirmed.topic", TOPIC)
                .arg("events.order-confirmed.group-id", GROUP)
                .readyWhen(STREAMER_READY, PROCESS_READY)
                .start();
            processes.add(process);
            return process;
        }

        private static int port(BootJarProcess process) {
            return Integer.parseInt(process.awaitLog(TOMCAT_PORT, Duration.ofSeconds(10)).group(1));
        }

        /** 참조 데이터(사용자 1, 브랜드 1, 상품 4)만 넣는다. 주문·결제 상태는 넣지 않는다. A: 상품1×2·상품2×1, B: 상품3×1·상품4×3. */
        private void seedReferenceData() {
            userId = insert("insert into `user` (point_balance, created_at, updated_at) values (0, utc_timestamp(6), utc_timestamp(6))");
            long brandId = insert("insert into brand (name, description, category, created_at, updated_at) "
                + "values ('w6-recovery', 'W6 누락 재현', 'test', utc_timestamp(6), utc_timestamp(6))");
            List<Long> products = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                products.add(insert("insert into product (name, price, brand_id, stock_remaining, created_at, updated_at) "
                    + "values (?, ?, ?, ?, utc_timestamp(6), utc_timestamp(6))", "w6-recovery-" + i, PRICE, brandId, INITIAL_STOCK));
            }
            itemsA = orderedItems(products.get(0), 2, products.get(1), 1);
            itemsB = orderedItems(products.get(2), 1, products.get(3), 3);
        }

        private static Map<Long, Integer> orderedItems(Long first, int firstQuantity, Long second, int secondQuantity) {
            Map<Long, Integer> items = new LinkedHashMap<>();
            items.put(first, firstQuantity);
            items.put(second, secondQuantity);
            return items;
        }

        /**
         * 6주차 실제 HTTP 경로: 주문(DRAFT) → 결제 시작(PG 거래 생성, 202 PENDING) → 그 거래를 SUCCESS로 조회하게 한 뒤 reconcile
         * (5주차 "같은 거래 조회 → 결과 적용", 확정 TX). transactionKey는 테스트 PG가 돌려준 응답에서 읽는다.
         */
        private Long placeAndConfirm(int port, Map<Long, Integer> items, String label) throws Exception {
            StringBuilder body = new StringBuilder("{\"items\":[");
            body.append(items.entrySet().stream()
                .map(item -> "{\"productId\":" + item.getKey() + ",\"quantity\":" + item.getValue() + "}")
                .collect(Collectors.joining(",")));
            body.append("]}");
            JsonNode created = http(port, "/api/v1/orders", body.toString(), 201);
            long orderId = created.path("data").path("id").asLong();

            int createsBefore = pgCreateEvents().size();
            JsonNode started = http(port, "/api/v1/orders/" + orderId + "/confirm",
                "{\"paymentAttemptKey\":\"w6-" + label + "-" + orderId + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}", 202);
            assertThat(started.path("data").path("status").asText()).as("%s 결제 시작 뒤 주문", label).isNotEqualTo("CONFIRMED");
            List<ServeEvent> creates = pgCreateEvents();
            assertThat(creates).as("%s 결제 시작이 만든 PG 거래 생성 요청", label).hasSize(createsBefore + 1);
            JsonNode accepted = MAPPER.readTree(creates.get(0).getResponse().getBodyAsString());
            String transactionKey = accepted.path("transactionKey").asText();
            pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + transactionKey))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + accepted.path("orderId").asText()
                        + "\",\"amount\":" + accepted.path("amount").asLong() + ",\"status\":\"SUCCESS\"}")));

            JsonNode confirmed = http(port, "/api/v1/orders/" + orderId + "/payment/reconcile", null, 200);
            assertThat(confirmed.path("data").path("status").asText()).as("%s reconcile 응답", label).isEqualTo("CONFIRMED");
            return orderId;
        }

        private JsonNode http(int port, String path, String body, int expectedStatus) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-USER-ID", String.valueOf(userId))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("POST %s 응답 (%s)", path, response.body()).isEqualTo(expectedStatus);
            return MAPPER.readTree(response.body());
        }

        /** 테스트 PG가 받은 거래 생성 요청. 최근 것이 앞이다. */
        private List<ServeEvent> pgCreateEvents() {
            return pg.getAllServeEvents().stream()
                .filter(event -> "POST".equals(event.getRequest().getMethod().getName())
                    && TestPg.PAYMENTS_PATH.equals(event.getRequest().getUrl()))
                .toList();
        }

        /** 거래 생성 요청마다 테스트 PG가 돌려준 transactionKey(오래된 것부터). 크기가 C, 서로 다른 값의 수가 T다. */
        private List<String> pgCreateTransactions() throws Exception {
            List<String> keys = new ArrayList<>();
            for (ServeEvent event : pgCreateEvents()) {
                keys.add(0, MAPPER.readTree(event.getResponse().getBodyAsString()).path("transactionKey").asText());
            }
            return keys;
        }

        // ------------------------------------------------------------ DB

        private long insert(String sql, Object... args) {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                return statement;
            }, keys);
            return keys.getKey().longValue();
        }

        /** 주문의 저장된 사실. confirmed_at은 DB 값(UTC) 그대로 계약 JSON 모양(µs, Z)으로 읽는다. */
        private StoredOrder stored(Long orderId) {
            Map<String, Object> order = jdbc.queryForMap(
                "select status, date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') as confirmed_at from orders where id = ?", orderId);
            Map<Long, Integer> items = new LinkedHashMap<>();
            jdbc.queryForList("select product_id, quantity from order_item where order_id = ? order by product_id", orderId)
                .forEach(row -> items.put(((Number) row.get("product_id")).longValue(), ((Number) row.get("quantity")).intValue()));
            Map<String, Object> attempt = jdbc.queryForMap("select status, transaction_key from payment_attempt where order_id = ?", orderId);
            Map<Long, Integer> stocks = new LinkedHashMap<>();
            for (Long productId : items.keySet()) {
                stocks.put(productId, jdbc.queryForObject("select stock_remaining from product where id = ?", Integer.class, productId));
            }
            return new StoredOrder(orderId, (String) order.get("status"), (String) order.get("confirmed_at"), items,
                (String) attempt.get("status"), (String) attempt.get("transaction_key"), stocks);
        }

        /** 확정 + 결제 SUCCESS + 예약이 한 번 소비됨(처음 재고 − 수량 그대로). */
        private static void assertConfirmedAndConsumed(StoredOrder order, Map<Long, Integer> items) {
            Map<Long, Integer> expectedStocks = new LinkedHashMap<>();
            items.forEach((productId, quantity) -> expectedStocks.put(productId, INITIAL_STOCK - quantity));
            assertAll(
                () -> assertThat(order.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(order.confirmedAt()).isNotBlank(),
                () -> assertThat(order.attemptStatus()).isEqualTo("SUCCESS"),
                () -> assertThat(order.transactionKey()).isNotBlank(),
                () -> assertThat(order.items()).containsExactlyInAnyOrderEntriesOf(items),
                () -> assertThat(order.stocks()).as("예약 소비").containsExactlyInAnyOrderEntriesOf(expectedStocks)
            );
        }

        private boolean tableExists(String table) {
            return count("select count(*) from information_schema.tables where table_schema = database() and table_name = ?", table) > 0;
        }

        private int outboxRowsOf(Long first, Long second) {
            if (!tableExists("outbox_event")) {
                return 0;
            }
            return count("select count(*) from outbox_event where order_id in (?, ?)", first, second);
        }

        private List<Map<String, Object>> activityRows(Long orderId) {
            if (!tableExists("product_purchase_activity")) {
                return List.of();
            }
            return jdbc.queryForList("select * from product_purchase_activity where order_id = ? order by id", orderId);
        }

        /** 구매 활동이 품목 수만큼 생길 때까지 기다리고, 수량이 품목과 같은지 본다. */
        private List<Map<String, Object>> awaitActivity(Long orderId, Map<Long, Integer> items) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            List<Map<String, Object>> rows = activityRows(orderId);
            while (rows.size() < items.size() && System.nanoTime() < deadline) {
                assertThat(streamer.isAlive()).as("streamer 프로세스가 기다리는 동안 살아 있다").isTrue();
                Thread.sleep(300);
                rows = activityRows(orderId);
            }
            Map<Long, Integer> quantities = new HashMap<>();
            rows.forEach(row -> quantities.merge(((Number) row.get("product_id")).longValue(), ((Number) row.get("quantity")).intValue(),
                Integer::sum));
            assertThat(rows).as("주문 %d의 구매 활동 행", orderId).hasSize(items.size());
            assertThat(quantities).as("주문 %d의 구매 활동 수량", orderId).containsExactlyInAnyOrderEntriesOf(items);
            return rows;
        }

        private int count(String sql, Object... args) {
            Integer count = jdbc.queryForObject(sql, Integer.class, args);
            return count == null ? 0 : count;
        }

        // ------------------------------------------------------------ Outbox

        private static OutboxEvent singleRow(OutboxEventRepository outbox, Long orderId) {
            List<OutboxEvent> rows = outbox.findAllByOrderId(orderId);
            assertThat(rows).as("주문 %d의 Outbox 행", orderId).hasSize(1);
            return rows.get(0);
        }

        private static boolean published(OutboxEventRepository outbox, Long orderId) {
            return singleRow(outbox, orderId).getStatus() == OutboxStatus.PUBLISHED;
        }

        /** eventId·confirmedAt·품목이 저장된 사실 그대로다 — 현재 상품 정보나 now()가 들어가지 않았다(D-5). */
        private static void assertRowBuiltFromStoredFacts(OutboxEvent row, StoredOrder order) throws Exception {
            JsonNode payload = MAPPER.readTree(row.getPayload());
            Map<Long, Integer> items = new LinkedHashMap<>();
            payload.path("items").forEach(item -> items.put(item.path("productId").asLong(), item.path("quantity").asInt()));
            assertAll(
                () -> assertThat(row.getEventId()).isEqualTo("order-confirmed:v1:" + order.orderId()),
                () -> assertThat(row.getOrderId()).isEqualTo(order.orderId()),
                () -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING),
                () -> assertThat(payload.path("eventId").asText()).isEqualTo(order.eventId()),
                () -> assertThat(payload.path("orderId").asLong()).isEqualTo(order.orderId()),
                () -> assertThat(payload.path("confirmedAt").asText()).as("confirmedAt = orders.confirmed_at").isEqualTo(order.confirmedAt()),
                () -> assertThat(items).as("품목 = order_item").containsExactlyInAnyOrderEntriesOf(order.items())
            );
        }

        // ------------------------------------------------------------ Kafka

        private ConsumerRecord<byte[], byte[]> awaitSingleRecord(String eventId) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            List<ConsumerRecord<byte[], byte[]>> records = recordsOf(eventId);
            while (records.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(300);
                records = recordsOf(eventId);
            }
            assertThat(records).as("%s의 record", eventId).hasSize(1);
            return records.get(0);
        }

        private int recordsWithEventId(String eventId) throws Exception {
            return recordsOf(eventId).size();
        }

        private List<ConsumerRecord<byte[], byte[]>> recordsOf(String eventId) throws Exception {
            List<ConsumerRecord<byte[], byte[]>> matching = new ArrayList<>();
            for (ConsumerRecord<byte[], byte[]> record : allRecords()) {
                if (eventId.equals(eventIdIn(record))) {
                    matching.add(record);
                }
            }
            return matching;
        }

        /** 이 topic의 record가 있는 모든 partition에서 consumer group의 commit 위치가 마지막 record를 지날 때까지 기다린다. */
        private void awaitAllCommitted() throws Exception {
            Map<Integer, Long> lastOffsets = new HashMap<>();
            allRecords().forEach(record -> lastOffsets.merge(record.partition(), record.offset(), Math::max));
            for (Map.Entry<Integer, Long> last : lastOffsets.entrySet()) {
                awaitCommittedPast(new TopicPartition(TOPIC, last.getKey()), last.getValue());
            }
        }

        private void awaitCommittedPast(TopicPartition partition, long lastOffset) throws Exception {
            long deadline = System.nanoTime() + WAIT.toNanos();
            long committed = -1;
            while (System.nanoTime() < deadline) {
                assertThat(streamer.isAlive()).as("streamer 프로세스가 기다리는 동안 살아 있다").isTrue();
                OffsetAndMetadata offset = committedOffsets().get(partition);
                committed = offset == null ? -1 : offset.offset();
                if (committed > lastOffset) {
                    return;
                }
                Thread.sleep(500);
            }
            throw new AssertionError("consumer group " + GROUP + "의 " + partition + " commit 위치가 " + WAIT + " 안에 record("
                + lastOffset + ")를 지나지 않았다. 지금 commit 위치: " + committed);
        }

        // ------------------------------------------------------------ 진단·정리

        /** 실패했을 때 실제 상태를 남긴다. 진단 중의 오류는 원래 실패를 가리지 않게 삼킨다. */
        void printState() {
            StringBuilder state = new StringBuilder("\n===== W6 재현·복구 실패 진단 =====\n");
            state.append("topic=").append(TOPIC).append(", group=").append(GROUP)
                .append(", A=").append(orderA).append(", B=").append(orderB).append('\n');
            if (jdbc != null) {
                append(state, "주문", () -> jdbc.queryForList("select id, status, confirmed_at from orders order by id"));
                append(state, "주문 품목", () -> jdbc.queryForList("select order_id, product_id, quantity from order_item order by order_id"));
                append(state, "결제 시도", () -> jdbc.queryForList("select order_id, status, transaction_key from payment_attempt order by order_id"));
                append(state, "상품 재고", () -> jdbc.queryForList("select id, stock_remaining from product order by id"));
                append(state, "Outbox 행", () -> tableExists("outbox_event")
                    ? jdbc.queryForList("select id, event_id, order_id, status, attempt_count, next_attempt_at, published_at, last_error, payload "
                        + "from outbox_event order by id")
                    : "(테이블 없음)");
                for (String table : STREAMER_TABLES) {
                    append(state, table, () -> tableExists(table) ? jdbc.queryForList("select * from " + table) : "(테이블 없음)");
                }
            }
            append(state, "topic record", () -> allRecords().stream()
                .map(record -> record.partition() + "@" + record.offset() + " key=" + new String(record.key(), StandardCharsets.UTF_8)
                    + " value=" + new String(record.value(), StandardCharsets.UTF_8))
                .toList());
            append(state, "consumer group commit", W6LossRecoveryE2ETest::committedOffsets);
            if (pg != null) {
                append(state, "PG 요청", () -> pg.getAllServeEvents().stream()
                    .map(event -> event.getRequest().getMethod() + " " + event.getRequest().getUrl() + " → " + event.getResponse().getStatus())
                    .toList());
            }
            for (BootJarProcess process : processes) {
                state.append("----- ").append(process.name()).append(" (alive=").append(process.isAlive()).append(", log=")
                    .append(process.logFile()).append(") 로그 끝부분 -----\n").append(process.tail()).append('\n');
            }
            System.out.println(state);
        }

        /** 맨 끝에서만 자기 자원을 정리한다 — 7주차 컨텍스트, streamer·남은 6주차 프로세스. 컨테이너·테스트 PG는 이 JVM이 끝날 때 함께 정리된다. */
        void close() {
            if (w7 != null) {
                w7.getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
            }
            processes.forEach(BootJarProcess::close);
        }
    }

    private interface Probe {
        Object read() throws Exception;
    }

    private static void append(StringBuilder state, String label, Probe probe) {
        try {
            state.append(label).append(": ").append(probe.read()).append('\n');
        } catch (Exception e) {
            state.append(label).append(": (읽기 실패 ").append(e).append(")\n");
        }
    }

    private static String describe(String label, StoredOrder order) {
        return label + ": orderId=" + order.orderId() + ", eventId=" + order.eventId() + ", status=" + order.status()
            + ", confirmedAt=" + order.confirmedAt() + ", items=" + order.items() + ", 결제 시도=" + order.attemptStatus()
            + ", PG transactionKey=" + order.transactionKey() + ", 재고=" + order.stocks();
    }

    private static String describe(OutboxEvent event) {
        return "OutboxEvent{id=" + event.getId() + ", eventId=" + event.getEventId() + ", status=" + event.getStatus()
            + ", attemptCount=" + event.getAttemptCount() + ", publishedAt=" + event.getPublishedAt() + ", lastError=" + event.getLastError() + "}";
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        assertThat(value).as("테스트 컨테이너 접속 정보 시스템 프로퍼티 %s", key).isNotBlank();
        return value;
    }

    /** 방금 비운 로컬 포트 — 아무도 듣지 않는 Kafka 주소(D-10 A). */
    private static String unusedLocalAddress() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "127.0.0.1:" + socket.getLocalPort();
        }
    }

    // ---------------------------------------------------------------- Kafka 읽기

    private static KafkaConsumer<byte[], byte[]> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    /** 이 테스트만 쓰는 topic의 record 전부. 지금의 끝 offset까지 읽었는지 확인하고 돌려준다. */
    private static List<ConsumerRecord<byte[], byte[]>> allRecords() {
        List<TopicPartition> partitions = IntStream.range(0, KafkaTestContainers.PARTITIONS)
            .mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!reached(consumer, end) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(reached(consumer, end)).as("끝 offset %s까지 읽기 (읽은 수 %d)", end, records.size()).isTrue();
            return records;
        }
    }

    private static boolean reached(KafkaConsumer<byte[], byte[]> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private static String eventIdIn(ConsumerRecord<byte[], byte[]> record) throws Exception {
        return MAPPER.readTree(new String(record.value(), StandardCharsets.UTF_8)).path("eventId").asText();
    }

    private static Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers()))) {
            return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        }
    }
}
