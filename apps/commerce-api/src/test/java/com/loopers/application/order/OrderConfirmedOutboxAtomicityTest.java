package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.payment.ApplyResult;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderConfirmedJson;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.outbox.OutboxEventRepositoryImpl;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 처음 CONFIRMED가 된 주문은 같은 TX에서 Outbox 행을 정확히 하나 갖고, 둘은 함께 commit되거나 함께 rollback된다
 * (docs/week7/decisions.md INV-701, D-6). PENDING·UNKNOWN·FAILED 결과는 행을 만들지 않는다.
 * 실제 Facade·MySQL·테스트 PG를 쓰고, 직렬화 포트와 Outbox 저장소 포트에만 스위치가 달린 래퍼를 끼운다
 * (6주차 D-16의 SwitchablePublisher와 같은 방식 — 운영 코드에 테스트 분기가 없다). 래퍼는 기본으로 진짜 구현에 넘긴다.
 *
 * <p>6주차 OrderConfirmedPublicationTest의 보장을 옮겨 왔다. 발행이 commit 뒤 메모리 큐에서 Outbox 행으로 바뀌었으므로
 * "topic에 기록이 하나"는 "Outbox 행이 하나"로, "발행 없음"은 "행 없음"으로 본다. Kafka까지 가는 일은 relay(batch 2)의 몫이다.
 *
 * <p>입력이 겹치지 않게 한다: 테스트 사이에 DB를 비우지 않아 주문·상품 ID가 클래스 안에서 겹치지 않는다(비우면 id가 1부터
 * 다시 쓰여 앞 테스트의 eventId와 같아진다). 검증은 자기 주문 ID로만 한다. DB는 클래스가 끝날 때 비운다.
 * 테스트 전체를 자동 rollback 트랜잭션으로 감싸지 않는다 — commit된 결과를 본다.
 */
@SpringBootTest
@Import(OrderConfirmedOutboxAtomicityTest.SwitchConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConfirmedOutboxAtomicityTest {

    private static final DateTimeFormatter CONTRACT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final long PRICE = 1_000L;
    private static final int INITIAL_STOCK = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private SwitchableSerializer serializer;

    @Autowired
    private SwitchableOutboxRepository outboxRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();

    @BeforeEach
    void setUp() {
        serializer.reset();
        outboxRepository.reset();
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    // ---------------------------------------------------------------- fixtures

    private record Fixture(Long userId, Long orderId, Map<Long, Integer> quantityByProductId) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }

        long amount() {
            return quantityByProductId.values().stream().mapToLong(quantity -> quantity * PRICE).sum();
        }

        String eventId() {
            return OrderConfirmedPayload.eventIdOf(orderId);
        }
    }

    /** 상품 2개(수량 2, 1)를 담은 주문을 PG 기본 응답(202 PENDING)으로 확정해 처리중으로 만든다. */
    private Fixture pending() {
        Fixture fixture = draft();
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command(fixture));
        assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING);
        return fixture;
    }

    private Fixture draft() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Map<Long, Integer> quantities = new HashMap<>();
        List<OrderItemRequest> items = new ArrayList<>();
        for (int quantity : new int[] {2, 1}) {
            Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
            quantities.put(productId, quantity);
            items.add(new OrderItemRequest(productId, quantity));
        }
        Long orderId = orderFacade.createOrder(userId, items).id();
        return new Fixture(userId, orderId, quantities);
    }

    /** 처리중 결제를 고객 결제 확인으로 SUCCESS 반영한다 — 5주차 "같은 거래 조회 → 결과 적용" 경로다. 고객이 받는 응답을 돌려준다. */
    private OrderInfo confirmBySuccess(Fixture fixture) {
        stubSingle(fixture, "SUCCESS");
        return orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
    }

    /** 결제 시도 키는 주문마다 다르게 쓴다 — 테스트 사이에 DB를 비우지 않으므로 같은 키는 "다른 주문에 쓴 키"가 된다. */
    private static ConfirmPaymentCommand command(Fixture fixture) {
        return new ConfirmPaymentCommand("key-" + fixture.orderId(), "TEST", "0000-0000-0000-0000");
    }

    private void stubSingle(Fixture fixture, String status) {
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(transactionJson(fixture, status))));
    }

    private static String transactionJson(Fixture fixture, String status) {
        return "{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"orderId\":\"" + fixture.providerOrderId()
            + "\",\"amount\":" + fixture.amount() + ",\"status\":\"" + status + "\"}";
    }

    private PaymentObservation success(Fixture fixture) {
        return new PaymentObservation(fixture.providerOrderId(), fixture.transactionKey(),
            PaymentObservation.Status.SUCCESS, fixture.amount());
    }

    private Order order(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow();
    }

    private PaymentAttemptStatus attemptStatus(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
    }

    /** 예약은 결제 시작(TX A)에서 잡히고, SUCCESS는 그대로 소비한다. 실패 반영만 돌려준다. 그래서 "예약 소비 = 예약된 채 그대로"다. */
    private void assertReservationConsumed(Fixture fixture) {
        fixture.quantityByProductId().forEach((productId, quantity) ->
            assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - quantity));
    }

    private List<OutboxEvent> rowsOf(Fixture fixture) {
        return outboxRepository.findAllByOrderId(fixture.orderId());
    }

    private int pgCreates() {
        return pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).size();
    }

    /**
     * DB에서 다시 읽은 사실로 계약 JSON을 직접 쓴다. 직렬화 구현을 부르지 않는 독립된 기대값이다 — 필드 순서·소수 6자리·UTC·
     * productId 오름차순이 계약대로인지를 글자로 본다.
     */
    private static String contractJsonOf(Order stored) {
        String items = stored.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .map(item -> "{\"productId\":" + item.getProductId() + ",\"quantity\":" + item.getQuantity() + "}")
            .collect(Collectors.joining(","));
        return "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:" + stored.getId()
            + "\",\"orderId\":" + stored.getId()
            + ",\"confirmedAt\":\"" + stored.getConfirmedAt().withZoneSameInstant(ZoneOffset.UTC).format(CONTRACT_TIME)
            + "\",\"items\":[" + items + "]}";
    }

    private static Set<String> contractFieldNames() throws IOException {
        String dir = System.getProperty("contract.dir");
        assertThat(dir).as("contract.dir 시스템 프로퍼티(build.gradle.kts tasks.test)").isNotBlank();
        Set<String> names = new TreeSet<>();
        MAPPER.readTree(Files.readString(Path.of(dir, "order-confirmed.v1.json"))).fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> fieldNames(String json) throws IOException {
        Set<String> names = new TreeSet<>();
        MAPPER.readTree(json).fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---------------------------------------------------------------- scenes

    @DisplayName("정상 1건: PG SUCCESS로 처음 확정되면 같은 TX에서 PENDING Outbox 행이 정확히 하나 생기고, "
        + "payload는 DB에서 다시 읽은 사실로 쓴 계약 JSON과 글자 그대로 같다. (INV-701, INV-702)")
    @Test
    void firstSuccess_savesOnePendingRow_withContractPayload() throws Exception {
        // arrange
        Fixture fixture = pending();

        // act
        OrderInfo response = confirmBySuccess(fixture);

        // assert
        Order stored = order(fixture);
        List<OutboxEvent> rows = rowsOf(fixture);
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        System.out.printf("[OrderConfirmed Outbox 정상 1건] eventId=%s, key=%s, payload=%s%n",
            row.getEventId(), row.getMessageKey(), row.getPayload());
        assertAll(
            () -> assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.SUCCESS),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(row.getEventId()).isEqualTo("order-confirmed:v1:" + fixture.orderId()),
            () -> assertThat(row.getOrderId()).isEqualTo(fixture.orderId()),
            () -> assertThat(row.getEventType()).isEqualTo(OrderConfirmedPayload.EVENT_TYPE),
            () -> assertThat(row.getSchemaVersion()).isEqualTo(OrderConfirmedPayload.SCHEMA_VERSION),
            () -> assertThat(row.getMessageKey()).isEqualTo(String.valueOf(fixture.orderId())),
            () -> assertThat(row.getAttemptCount()).isZero(),
            () -> assertThat(row.getPublishedAt()).isNull(),
            () -> assertThat(row.getLastError()).isNull(),
            () -> assertThat(row.getNextAttemptAt()).isNotNull(),
            () -> assertThat(row.getCreatedAt()).isNotNull(),
            () -> assertThat(row.getPayload()).isEqualTo(contractJsonOf(stored)),
            () -> assertThat(fieldNames(row.getPayload())).isEqualTo(contractFieldNames()),
            () -> assertThat(outboxRepository.findByEventId(fixture.eventId())).map(OutboxEvent::getId).contains(row.getId())
        );
    }

    @DisplayName("확정 순간의 메모리 시각(payload에 실린 값)과 고객 응답, DB에서 다시 읽은 시각이 같은 순간이고 글자도 같다. (6주차 D-6)")
    @Test
    void confirmedAtInPayloadEqualsDatabase() throws Exception {
        // arrange
        Fixture fixture = pending();

        // act
        OrderInfo response = confirmBySuccess(fixture);

        // assert
        String inPayload = MAPPER.readTree(rowsOf(fixture).get(0).getPayload()).get("confirmedAt").asText();
        ZonedDateTime stored = order(fixture).getConfirmedAt();
        assertAll(
            () -> assertThat(inPayload).isEqualTo(stored.withZoneSameInstant(ZoneOffset.UTC).format(CONTRACT_TIME)),
            () -> assertThat(response.confirmedAt().toInstant()).isEqualTo(stored.toInstant())
        );
    }

    /**
     * 6주차 registerFailure_keepsConfirmation_andCounts("등록이 실패해도 확정은 commit")의 뜻을 7주차 D-6이 일부러 뒤집었다.
     * 이제는 보낼 사실을 만들지 못하면 확정도 rollback한다 — 확정만 남으면 6주차와 같은 누락이 생기기 때문이다.
     */
    @DisplayName("직렬화 실패 주입: 확정 TX 전체가 rollback된다 — 주문은 결제 진행 중, 결제 시도는 PENDING, 예약은 그대로, "
        + "Outbox 행 0개, PG 생성 요청 추가 없음. (W6 D-26을 뒤집은 W7 D-6)")
    @Test
    void serializationFailure_rollsBackConfirmation() {
        // arrange
        Fixture fixture = pending();
        serializer.failFor(fixture.orderId());
        int createsBefore = pgCreates();

        // act
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> confirmBySuccess(fixture));

        // assert
        assertAll(
            () -> assertThat(thrown).hasMessageContaining("주입된 직렬화 실패"),
            () -> assertThat(serializer.callsFor(fixture.orderId())).isEqualTo(1),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order(fixture).getConfirmedAt()).isNull(),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(rowsOf(fixture)).isEmpty(),
            () -> assertThat(pgCreates()).isEqualTo(createsBefore)
        );
    }

    @DisplayName("Outbox 저장 실패 주입: 확정 TX 전체가 rollback된다 — 주문은 결제 진행 중, 결제 시도는 PENDING, 예약은 그대로, "
        + "Outbox 행 0개, PG 생성 요청 추가 없음. (W7 D-6)")
    @Test
    void outboxInsertFailure_rollsBackConfirmation() {
        // arrange
        Fixture fixture = pending();
        outboxRepository.failSaveFor(fixture.orderId());
        int createsBefore = pgCreates();

        // act
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> confirmBySuccess(fixture));

        // assert
        assertAll(
            () -> assertThat(thrown).hasMessageContaining("주입된 Outbox 저장 실패"),
            () -> assertThat(outboxRepository.saveCallsFor(fixture.orderId())).isEqualTo(1),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order(fixture).getConfirmedAt()).isNull(),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(rowsOf(fixture)).isEmpty(),
            () -> assertThat(pgCreates()).isEqualTo(createsBefore)
        );
    }

    @DisplayName("rollback 뒤 다시 적용: 같은 거래를 다시 조회해 적용하면 CONFIRMED가 되고, 예약은 한 번만 소비되며, Outbox 행은 정확히 하나다. "
        + "PG 생성 요청은 다시 보내지 않는다. (W7 D-6 — PG 성공을 잃지 않는다)")
    @Test
    void reapplyAfterRollback_confirmsWithOneRow() {
        // arrange — 직렬화 실패와 저장 실패로 두 번 rollback된 주문
        Fixture fixture = pending();
        int createsBefore = pgCreates();
        serializer.failFor(fixture.orderId());
        assertThrows(RuntimeException.class, () -> confirmBySuccess(fixture));
        serializer.reset();
        outboxRepository.failSaveFor(fixture.orderId());
        assertThrows(RuntimeException.class, () -> confirmBySuccess(fixture));
        outboxRepository.reset();

        // act
        OrderInfo response = confirmBySuccess(fixture);

        // assert
        Order stored = order(fixture);
        List<OutboxEvent> rows = rowsOf(fixture);
        assertAll(
            () -> assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.SUCCESS),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(rows).hasSize(1),
            () -> assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING),
            () -> assertThat(rows.get(0).getPayload()).isEqualTo(contractJsonOf(stored)),
            () -> assertThat(pgCreates()).isEqualTo(createsBefore)
        );
    }

    @DisplayName("행 없음: 처리중(PENDING)·응답 유실(UNKNOWN)·실패(FAILED)는 Outbox 행을 만들지 않는다. (INV-701)")
    @Test
    void nonSuccessResults_saveNoRow() throws Exception {
        // arrange & act
        Fixture pending = pending();

        Fixture failed = pending();
        stubSingle(failed, "FAILED");
        OrderInfo failedResponse = orderFacade.reconcilePayment(failed.orderId(), failed.userId());

        // UNKNOWN은 마지막에 만든다 — 늦게 응답하는 create stub이 남아 있으면 뒤의 확정도 UNKNOWN이 된다.
        Fixture unknown = unknown();

        // assert
        assertAll(
            () -> assertThat(attemptStatus(pending)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(failedResponse.status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
            () -> assertThat(attemptStatus(unknown)).isEqualTo(PaymentAttemptStatus.UNKNOWN),
            () -> assertThat(rowsOf(pending)).isEmpty(),
            () -> assertThat(rowsOf(failed)).isEmpty(),
            () -> assertThat(rowsOf(unknown)).isEmpty(),
            () -> assertThat(serializer.callsFor(pending.orderId())).isZero(),
            () -> assertThat(serializer.callsFor(failed.orderId())).isZero(),
            () -> assertThat(serializer.callsFor(unknown.orderId())).isZero()
        );
    }

    /** 생성 응답을 잃고 바로 재조회도 0건이라 UNKNOWN이 된 결제. 늦게 기록되는 생성 요청을 기다려 다음 테스트로 넘기지 않는다. */
    private Fixture unknown() throws InterruptedException {
        Fixture fixture = draft();
        int before = pgCreates();
        pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(202).withFixedDelay(1_000)));
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command(fixture));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pgCreates() <= before && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return fixture;
    }

    /**
     * 행 없음(rollback): SUCCESS 반영을 바깥 트랜잭션 안에서 하고 그 트랜잭션을 rollback한다. apply는 바깥 트랜잭션에 합류하므로
     * 확정과 Outbox 저장까지는 실제로 일어나지만(그 안에서 행이 보이는 것을 확인한다), commit이 없어 둘 다 남지 않는다.
     * 운영 경로에서 확정 트랜잭션이 commit 단계에서 실패하는 경우와 같다. 6주차 단위 테스트의 doesNotPublish_onRollback·
     * rejectsRegistration_withoutTransaction("commit을 보장할 수 없으면 발행하지 않는다")의 보장이 여기로 왔다.
     */
    @DisplayName("행 없음: 확정 트랜잭션이 rollback되면 확정도 Outbox 행도 남지 않는다. (INV-701)")
    @Test
    void rolledBackConfirmation_savesNoRow() {
        // arrange
        Fixture fixture = pending();
        int[] rowsInside = new int[1];

        // act
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ApplyResult result = paymentResultApplier.apply(fixture.orderId(), success(fixture));
            assertThat(result).isEqualTo(ApplyResult.SUCCEEDED);
            rowsInside[0] = rowsOf(fixture).size();
            status.setRollbackOnly();
        });

        // assert
        assertAll(
            () -> assertThat(rowsInside[0]).as("같은 TX 안에서는 행이 보인다").isEqualTo(1),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(rowsOf(fixture)).isEmpty()
        );
    }

    @DisplayName("중복 결과 적용: 같은 PG 성공을 여러 번 반영해도 Outbox 행은 하나이고 재고도 한 번만 줄어 있다.")
    @Test
    void repeatedSuccess_savesOneRow() {
        // arrange
        Fixture fixture = pending();
        confirmBySuccess(fixture);

        // act — 같은 결과가 다른 입구(콜백 워커·회복 작업)로 다시 들어온 것과 같다. 모두 apply를 지난다.
        ApplyResult second = paymentResultApplier.apply(fixture.orderId(), success(fixture));
        ApplyResult third = paymentResultApplier.apply(fixture.orderId(), success(fixture));
        OrderInfo again = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

        // assert
        assertAll(
            () -> assertThat(second).isEqualTo(ApplyResult.NO_EFFECT),
            () -> assertThat(third).isEqualTo(ApplyResult.NO_EFFECT),
            () -> assertThat(again.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(rowsOf(fixture)).hasSize(1),
            () -> assertThat(serializer.callsFor(fixture.orderId())).isEqualTo(1),
            () -> assertReservationConsumed(fixture)
        );
    }

    @DisplayName("event_id unique가 마지막 방어선이다: 같은 eventId의 행을 하나 더 저장하면 DB가 거절하고 행은 하나로 남는다.")
    @Test
    void duplicateEventId_isRejectedByDatabase() {
        // arrange
        Fixture fixture = pending();
        confirmBySuccess(fixture);
        OutboxEvent existing = rowsOf(fixture).get(0);

        // act
        assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> outboxRepository.save(OutboxEvent.pending(existing.getEventId(),
                existing.getOrderId(), existing.getEventType(), existing.getSchemaVersion(), existing.getMessageKey(),
                existing.getPayload(), ZonedDateTime.now()))));

        // assert
        assertThat(rowsOf(fixture)).hasSize(1);
    }

    // ---------------------------------------------------------------- 이음매의 스위치

    @TestConfiguration
    static class SwitchConfig {
        @Bean
        @Primary
        SwitchableSerializer switchableSerializer(OrderConfirmedJson orderConfirmedJson) {
            return new SwitchableSerializer(orderConfirmedJson);
        }

        @Bean
        @Primary
        SwitchableOutboxRepository switchableOutboxRepository(OutboxEventRepositoryImpl outboxEventRepositoryImpl) {
            return new SwitchableOutboxRepository(outboxEventRepositoryImpl);
        }
    }

    /** 진짜 직렬화 구현을 감싼다. 지정한 주문에서만 실패를 던지고, 받은 호출을 주문별로 센다. */
    static class SwitchableSerializer implements OrderConfirmedSerializer {

        private final OrderConfirmedSerializer delegate;
        private final Map<Long, Integer> calls = new ConcurrentHashMap<>();
        private final Set<Long> failing = ConcurrentHashMap.newKeySet();

        SwitchableSerializer(OrderConfirmedSerializer delegate) {
            this.delegate = delegate;
        }

        void reset() {
            calls.clear();
            failing.clear();
        }

        void failFor(Long orderId) {
            failing.add(orderId);
        }

        int callsFor(Long orderId) {
            return calls.getOrDefault(orderId, 0);
        }

        @Override
        public String write(OrderConfirmedPayload payload) {
            calls.merge(payload.orderId(), 1, Integer::sum);
            if (failing.contains(payload.orderId())) {
                throw new IllegalStateException("주입된 직렬화 실패: orderId=" + payload.orderId());
            }
            return delegate.write(payload);
        }
    }

    /** 진짜 Outbox 저장소를 감싼다. 지정한 주문의 저장에서만 실패를 던진다. 조회는 그대로 넘긴다. */
    static class SwitchableOutboxRepository implements OutboxEventRepository {

        private final OutboxEventRepository delegate;
        private final Map<Long, Integer> saveCalls = new ConcurrentHashMap<>();
        private final Set<Long> failing = ConcurrentHashMap.newKeySet();

        SwitchableOutboxRepository(OutboxEventRepository delegate) {
            this.delegate = delegate;
        }

        void reset() {
            saveCalls.clear();
            failing.clear();
        }

        void failSaveFor(Long orderId) {
            failing.add(orderId);
        }

        int saveCallsFor(Long orderId) {
            return saveCalls.getOrDefault(orderId, 0);
        }

        @Override
        public OutboxEvent save(OutboxEvent event) {
            saveCalls.merge(event.getOrderId(), 1, Integer::sum);
            if (failing.contains(event.getOrderId())) {
                throw new IllegalStateException("주입된 Outbox 저장 실패: orderId=" + event.getOrderId());
            }
            return delegate.save(event);
        }

        @Override
        public Optional<OutboxEvent> findByEventId(String eventId) {
            return delegate.findByEventId(eventId);
        }

        @Override
        public List<OutboxEvent> findAllByOrderId(Long orderId) {
            return delegate.findAllByOrderId(orderId);
        }

        // relay용 조회·집기(batch 2)는 이 테스트의 관심 밖이다. 그대로 넘긴다.
        @Override
        public Optional<OutboxEvent> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public List<Long> findDueIds(ZonedDateTime now, int limit) {
            return delegate.findDueIds(now, limit);
        }

        @Override
        public int claim(Long id, ZonedDateTime now, ZonedDateTime leaseUntil) {
            return delegate.claim(id, now, leaseUntil);
        }

        @Override
        public long countByStatus(OutboxStatus status) {
            return delegate.countByStatus(status);
        }

        @Override
        public Optional<ZonedDateTime> findOldestCreatedAt(OutboxStatus status) {
            return delegate.findOldestCreatedAt(status);
        }
    }
}
