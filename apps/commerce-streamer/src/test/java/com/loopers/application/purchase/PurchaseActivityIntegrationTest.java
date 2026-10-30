package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderConfirmedContractViolation;
import com.loopers.domain.purchase.OrderConfirmedContractViolationRepository;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.PurchaseActivityConflict;
import com.loopers.domain.purchase.PurchaseActivityConflictRepository;
import com.loopers.domain.purchase.PurchaseFact;
import com.loopers.domain.purchase.RecordAddress;
import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.loopers.support.KafkaProbe.item;
import static com.loopers.support.KafkaProbe.json;
import static com.loopers.support.KafkaProbe.nextId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 Kafka·MySQL·listener로 기록 하나의 결말을 확인한다(docs/week6/design.md 5번 유스케이스, INV-604~607).
 *
 * <p>입력이 겹치지 않게 한다(docs/week6/decisions.md D-13): topic과 group은 이 클래스만의 고유 suffix이고, 주문·상품 ID는
 * 테스트마다 새로 받는다. DB를 테스트마다 비우지 않으므로 검증은 자기 주문·상품으로만 한다(테이블 전체를 세지 않는다).
 * 기다림은 모두 상한이 있다 — committed offset이 그 기록 다음 위치에 닿을 때까지 Awaitility로 기다린다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseActivityIntegrationTest {

    private static final String TOPIC = KafkaProbe.unique("order-events-it");
    private static final String GROUP = KafkaProbe.unique("purchase-activity-it");
    private static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
        registry.add("events.order-confirmed.group-id", () -> GROUP);
    }

    @Autowired
    private PurchaseActivityFacade facade;

    @Autowired
    private ProcessedOrderConfirmationRepository processedRepository;

    /** 품목 insert 도중 실패를 넣는 이음매. 스텁하지 않으면 진짜 구현 그대로다. */
    @MockitoSpyBean
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private PurchaseActivityConflictRepository conflictRepository;

    @Autowired
    private OrderConfirmedContractViolationRepository violationRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private KafkaProbe kafka;

    @BeforeAll
    void openProbe() {
        kafka = new KafkaProbe(TOPIC, GROUP);
    }

    @AfterAll
    void cleanUp() {
        kafka.close();
        databaseCleanUp.truncateAllTables();
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** 이 기록이 처리되고 ack돼 group의 committed offset이 그 다음 위치에 닿을 때까지 기다린다. */
    private void awaitAcked(RecordMetadata sent) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100))
            .until(() -> kafka.committed(sent.partition()) >= sent.offset() + 1);
    }

    private double rejected(String kind) {
        return meterRegistry.get(DeliveryProblemRecorder.METRIC)
            .tag(DeliveryProblemRecorder.KIND_TAG, kind).counter().count();
    }

    private List<ProductPurchaseActivity> activities(long orderId) {
        return activityRepository.findAllByOrderId(orderId);
    }

    @DisplayName("처음 보는 확정: 처리 기록 1건과 품목 수만큼 구매 활동이 남고, committed offset이 그 기록 다음으로 넘어간다.")
    @Test
    void firstEvent_recordsOnceAndAcks() {
        // arrange
        long orderId = nextId();
        long p1 = nextId();
        long p2 = nextId();
        ZonedDateTime confirmedAt = now();

        // act
        RecordMetadata sent = kafka.send(String.valueOf(orderId), json(orderId, confirmedAt, item(p1, 2), item(p2, 1)));
        awaitAcked(sent);

        // assert
        System.out.printf("[구매 활동 처음 확정] partition=%d, offset=%d → committed=%d%n",
            sent.partition(), sent.offset(), kafka.committed(sent.partition()));
        ProcessedOrderConfirmation processed = processedRepository.findByOrderId(orderId).orElseThrow();
        List<ProductPurchaseActivity> activities = activities(orderId);
        assertAll(
            () -> assertThat(processed.getEventId()).isEqualTo("order-confirmed:v1:" + orderId),
            () -> assertThat(processed.getFact().value()).isEqualTo(
                PurchaseFact.of(orderId, confirmedAt, List.of(new OrderConfirmation.Item(p1, 2), new OrderConfirmation.Item(p2, 1))).value()),
            () -> assertThat(activities).extracting(ProductPurchaseActivity::getProductId).containsExactly(p1, p2),
            () -> assertThat(activities).extracting(ProductPurchaseActivity::getQuantity).containsExactly(2, 1),
            () -> assertThat(activities).allSatisfy(a -> assertThat(a.getConfirmedAt().toInstant()).isEqualTo(confirmedAt.toInstant())),
            () -> assertThat(kafka.committed(sent.partition())).isEqualTo(sent.offset() + 1)
        );
    }

    @DisplayName("같은 eventId·같은 사실이 다시 오면(재전달·재발행) 아무것도 바뀌지 않고 ack된다.")
    @Test
    void sameFactAgain_isNoOp() {
        // arrange
        long orderId = nextId();
        long product = nextId();
        String event = json(orderId, now(), item(product, 3));
        awaitAcked(kafka.send(String.valueOf(orderId), event));

        // act
        RecordMetadata again = kafka.send(String.valueOf(orderId), event);
        awaitAcked(again);

        // assert
        assertAll(
            () -> assertThat(processedRepository.findByOrderId(orderId)).isPresent(),
            () -> assertThat(activities(orderId)).hasSize(1),
            () -> assertThat(activities(orderId).get(0).getQuantity()).isEqualTo(3),
            () -> assertThat(conflictRepository.findAllByOrderId(orderId)).isEmpty()
        );
    }

    @DisplayName("같은 eventId인데 수량이 다르면 conflict 1건(처음 사실·새 사실)을 남기고 ack한다. 구매 활동은 그대로다.")
    @Test
    void sameEventDifferentFact_recordsConflict() {
        // arrange
        long orderId = nextId();
        long product = nextId();
        ZonedDateTime confirmedAt = now();
        awaitAcked(kafka.send(String.valueOf(orderId), json(orderId, confirmedAt, item(product, 2))));
        double before = rejected(DeliveryProblemRecorder.KIND_CONFLICT);

        // act
        RecordMetadata changed = kafka.send(String.valueOf(orderId), json(orderId, confirmedAt, item(product, 5)));
        awaitAcked(changed);

        // assert
        List<PurchaseActivityConflict> conflicts = conflictRepository.findAllByOrderId(orderId);
        assertAll(
            () -> assertThat(conflicts).hasSize(1),
            () -> assertThat(conflicts.get(0).getReason()).isEqualTo(PurchaseActivityConflict.Reason.SAME_EVENT_DIFFERENT_FACT),
            () -> assertThat(conflicts.get(0).getFirstFact()).endsWith("items=" + product + ":2"),
            () -> assertThat(conflicts.get(0).getNewFact()).endsWith("items=" + product + ":5"),
            () -> assertThat(conflicts.get(0).getAddress()).isEqualTo(new RecordAddress(TOPIC, changed.partition(), changed.offset())),
            () -> assertThat(activities(orderId)).extracting(ProductPurchaseActivity::getQuantity).containsExactly(2),
            () -> assertThat(rejected(DeliveryProblemRecorder.KIND_CONFLICT) - before).isEqualTo(1.0)
        );
    }

    @DisplayName("같은 orderId가 다른 eventId로 다시 오면, 내용이 같아도 conflict 1건(두 eventId)을 남기고 ack한다. 구매 활동은 그대로다.")
    @Test
    void sameOrderDifferentEvent_recordsConflictEvenIfSameContent() {
        // arrange
        long orderId = nextId();
        long product = nextId();
        ZonedDateTime confirmedAt = now();
        awaitAcked(kafka.send(String.valueOf(orderId), json(orderId, confirmedAt, item(product, 1))));
        double before = rejected(DeliveryProblemRecorder.KIND_CONFLICT);

        // act
        RecordMetadata sent = kafka.send(String.valueOf(orderId),
            json("order-confirmed:v2:" + orderId, orderId, confirmedAt, item(product, 1)));
        awaitAcked(sent);

        // assert
        List<PurchaseActivityConflict> conflicts = conflictRepository.findAllByOrderId(orderId);
        assertAll(
            () -> assertThat(conflicts).hasSize(1),
            () -> assertThat(conflicts.get(0).getReason()).isEqualTo(PurchaseActivityConflict.Reason.SAME_ORDER_DIFFERENT_EVENT),
            () -> assertThat(conflicts.get(0).getFirstEventId()).isEqualTo("order-confirmed:v1:" + orderId),
            () -> assertThat(conflicts.get(0).getEventId()).isEqualTo("order-confirmed:v2:" + orderId),
            () -> assertThat(conflicts.get(0).getFirstFact()).isEqualTo(conflicts.get(0).getNewFact()),
            () -> assertThat(conflicts.get(0).getAddress()).isEqualTo(new RecordAddress(TOPIC, sent.partition(), sent.offset())),
            () -> assertThat(activities(orderId)).extracting(ProductPurchaseActivity::getQuantity).containsExactly(1),
            () -> assertThat(rejected(DeliveryProblemRecorder.KIND_CONFLICT) - before).isEqualTo(1.0)
        );
    }

    @DisplayName("계약을 어긴 기록(깨진 바이트·key 불일치·같은 상품 두 줄·빈 items)은 원본 바이트 그대로 계약 깨짐으로 남기고 ack하며, "
        + "뒤따르는 정상 기록은 막히지 않고 처리된다.")
    @Test
    void contractViolations_areRecordedAndDoNotBlock() {
        // arrange
        long orderId = nextId();
        long product = nextId();
        int partition = 0;
        byte[] broken = {'{', '"', 'e', (byte) 0xFF, (byte) 0xFE, 0x00, '}'};
        byte[] keyMismatch = json(orderId, now(), item(product, 1)).getBytes(StandardCharsets.UTF_8);
        byte[] duplicateProduct = json(orderId, now(), item(product, 2), item(product, 1)).getBytes(StandardCharsets.UTF_8);
        byte[] emptyItems = json(orderId, now()).getBytes(StandardCharsets.UTF_8);
        double before = rejected(DeliveryProblemRecorder.KIND_CONTRACT_VIOLATION);

        // act — 같은 partition에 차례로 보낸다. 앞의 기록이 멈춤을 일으켰다면 뒤의 정상 기록은 처리되지 않는다.
        List<RecordMetadata> violations = List.of(
            kafka.send(partition, String.valueOf(orderId), broken),
            kafka.send(partition, String.valueOf(orderId + 1), keyMismatch),
            kafka.send(partition, String.valueOf(orderId), duplicateProduct),
            kafka.send(partition, String.valueOf(orderId), emptyItems));
        RecordMetadata valid = kafka.send(partition, String.valueOf(orderId),
            json(orderId, now(), item(product, 4)).getBytes(StandardCharsets.UTF_8));
        awaitAcked(valid);

        // assert
        List<byte[]> raws = List.of(broken, keyMismatch, duplicateProduct, emptyItems);
        List<String> keys = List.of(String.valueOf(orderId), String.valueOf(orderId + 1), String.valueOf(orderId), String.valueOf(orderId));
        for (int i = 0; i < violations.size(); i++) {
            RecordMetadata sent = violations.get(i);
            OrderConfirmedContractViolation recorded = violationRepository
                .findByAddress(new RecordAddress(TOPIC, sent.partition(), sent.offset())).orElseThrow();
            assertThat(recorded.getRawValue()).as("원본 바이트 %d", i).isEqualTo(raws.get(i));
            assertThat(recorded.getRecordKey()).isEqualTo(keys.get(i));
            System.out.printf("[계약 깨짐] offset=%d, reason=%s%n", sent.offset(), recorded.getReason());
        }
        assertAll(
            () -> assertThat(activities(orderId)).extracting(ProductPurchaseActivity::getQuantity).containsExactly(4),
            () -> assertThat(rejected(DeliveryProblemRecorder.KIND_CONTRACT_VIOLATION) - before).isEqualTo(4.0)
        );
    }

    @DisplayName("같은 주소의 계약 깨짐을 다시 받으면(기록 뒤 ack 전에 죽은 재전달) 한 번만 남고 지표도 한 번만 센다.")
    @Test
    void redeliveredViolation_isRecordedAndCountedOnce() {
        // arrange
        RecordAddress address = new RecordAddress(TOPIC, 1, nextId());
        byte[] broken = "not json".getBytes(StandardCharsets.UTF_8);
        double before = rejected(DeliveryProblemRecorder.KIND_CONTRACT_VIOLATION);

        // act
        facade.handle(address, "k", broken);
        facade.handle(address, "k", broken);

        // assert
        assertThat(violationRepository.findByAddress(address)).isPresent();
        assertThat(rejected(DeliveryProblemRecorder.KIND_CONTRACT_VIOLATION) - before).isEqualTo(1.0);
    }

    @DisplayName("같은 이벤트를 두 스레드가 동시에 처리해도(좀비 consumer) 효과는 한 번이고 conflict도 없다. (D-10)")
    @Test
    void concurrentDuplicate_appliesOnce() throws Exception {
        // arrange
        long orderId = nextId();
        long p1 = nextId();
        long p2 = nextId();
        byte[] event = json(orderId, now(), item(p1, 1), item(p2, 2)).getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RecordAddress address = new RecordAddress(TOPIC, 1, nextId());
            tasks.add(() -> {
                start.await();
                facade.handle(address, String.valueOf(orderId), event);
                return null;
            });
        }

        // act
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
        pool.shutdown();

        // assert
        assertAll(
            () -> assertThat(processedRepository.findByOrderId(orderId)).isPresent(),
            () -> assertThat(activities(orderId)).extracting(ProductPurchaseActivity::getQuantity).containsExactly(1, 2),
            () -> assertThat(conflictRepository.findAllByOrderId(orderId)).isEmpty()
        );
    }

    @DisplayName("품목 insert 도중 실패하면 처리 기록과 앞서 넣은 품목까지 전부 롤백되고, 예외가 그대로 나간다(ack 안 함). (INV-604)")
    @Test
    void failureMidItems_rollsBackEverything() {
        // arrange
        long orderId = nextId();
        long p1 = nextId();
        long p2 = nextId();
        doThrow(new DataAccessResourceFailureException("주입된 DB 장애"))
            .when(activityRepository).insert(argThat(a -> a != null && a.getProductId().equals(p2)));
        byte[] event = json(orderId, now(), item(p1, 1), item(p2, 2)).getBytes(StandardCharsets.UTF_8);

        // act
        assertThrows(DataAccessResourceFailureException.class,
            () -> facade.handle(new RecordAddress(TOPIC, 1, nextId()), String.valueOf(orderId), event));

        // assert
        assertAll(
            () -> assertThat(processedRepository.findByOrderId(orderId)).isEmpty(),
            () -> assertThat(activities(orderId)).isEmpty(),
            () -> assertThat(conflictRepository.findAllByOrderId(orderId)).isEmpty()
        );
    }
}
