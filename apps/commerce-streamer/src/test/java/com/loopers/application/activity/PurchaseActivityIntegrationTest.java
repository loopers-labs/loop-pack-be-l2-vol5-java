package com.loopers.application.activity;

import com.loopers.domain.activity.OrderConfirmationContractException;
import com.loopers.domain.activity.ProductPurchaseActivityModel;
import com.loopers.domain.activity.PurchaseActivityRepository;
import com.loopers.infrastructure.activity.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.activity.ProductPurchaseActivityJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedMessageParser;
import com.loopers.support.OrderConfirmedJson;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.loopers.application.activity.PurchaseActivityFacade.Result.ALREADY_PROCESSED;
import static com.loopers.application.activity.PurchaseActivityFacade.Result.RECORDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * OrderConfirmed → 상품 구매 활동의 DB 정합성 (실제 MySQL). listener 는 띄우지 않고 실제 parser·Facade 를 직접 부른다.
 * Kafka 재전달은 KafkaDeliveryTest, listener 의 실패·ack 는 OrderConfirmedConsumerTest 가 본다.
 */
@SpringBootTest(properties = "kafka.consumers.purchase-activity.auto-startup=false")
class PurchaseActivityIntegrationTest {

    @Autowired
    private PurchaseActivityFacade purchaseActivityFacade;
    @Autowired
    private OrderConfirmedMessageParser parser;
    @MockitoSpyBean
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;
    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PurchaseActivityFacade.Result consume(String json) {
        return consume(json, jsonOrderId(json));
    }

    private PurchaseActivityFacade.Result consume(String json, String key) {
        return purchaseActivityFacade.consume(parser.parse(key, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String jsonOrderId(String json) {
        return json.replaceAll(".*\"orderId\":(\\d+).*", "$1");
    }

    private int purchasedQuantity(Long productId) {
        return activityRepository.findAll().stream()
            .filter(activity -> activity.getProductId().equals(productId))
            .mapToInt(ProductPurchaseActivityModel::getQuantity)
            .sum();
    }

    @DisplayName("주문 42(상품 7×2, 8×1)의 확정을 받으면 완료 기록 1건과 상품별 활동 2행을 수량·확정 시각 그대로 저장한다.")
    @Test
    void recordsCompletionAndActivities() {
        PurchaseActivityFacade.Result result = consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));

        assertThat(result).isEqualTo(RECORDED);
        assertThat(processedRepository.findAll()).extracting("eventId", "orderId")
            .containsExactly(tuple("order-confirmed:v1:42", 42L));
        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(42L))
            .extracting(ProductPurchaseActivityModel::getProductId, ProductPurchaseActivityModel::getQuantity,
                activity -> activity.getConfirmedAt().toInstant())
            .containsExactly(
                tuple(7L, 2, Instant.parse(OrderConfirmedJson.CONFIRMED_AT)),
                tuple(8L, 1, Instant.parse(OrderConfirmedJson.CONFIRMED_AT)));
    }

    @DisplayName("같은 사실의 재전달(품목 순서만 다름)은 정상 no-op. 활동은 여전히 2행. 다른 주문 43(상품 7×3)은 새 활동이라 총 3행, 상품 7 구매 수량 합 5.")
    @Test
    void redeliveryIsNoOp_andOtherOrderAdds() {
        consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));

        PurchaseActivityFacade.Result again = consume(OrderConfirmedJson.of(42, 8, 1, 7, 2));
        assertThat(again).isEqualTo(ALREADY_PROCESSED);
        assertThat(activityRepository.count()).isEqualTo(2);

        assertThat(consume(OrderConfirmedJson.of(43, 7, 3))).isEqualTo(RECORDED);
        assertThat(activityRepository.count()).isEqualTo(3);
        assertThat(purchasedQuantity(7L)).isEqualTo(5);
        assertThat(processedRepository.count()).isEqualTo(2);
    }

    @DisplayName("같은 eventId 로 수량·확정 시각이 다른 사실이 오면 계약 위반. 추가 구매를 만들지 않는다.")
    @Test
    void conflictingPayloadForSameEvent_isRejected() {
        consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));

        assertThatThrownBy(() -> consume(OrderConfirmedJson.of(42, 7, 3, 8, 1)))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> consume(OrderConfirmedJson.of("order-confirmed:v1:42", 42, "2026-09-07T02:00:00Z", 7, 2, 8, 1)))
            .isInstanceOf(OrderConfirmationContractException.class);

        assertThat(activityRepository.findByOrderIdOrderByProductIdAsc(42L))
            .extracting(ProductPurchaseActivityModel::getProductId, ProductPurchaseActivityModel::getQuantity)
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
        assertThat(processedRepository.count()).isEqualTo(1);
    }

    @DisplayName("같은 주문을 다른 eventId 로 보내면 계약 위반. 새 구매로 더하지 않는다.")
    @Test
    void sameOrderWithOtherEventId_isRejected() {
        consume(OrderConfirmedJson.of(42, 7, 2));

        assertThatThrownBy(() -> consume(OrderConfirmedJson.of("order-confirmed:v1:42:retry", 42, OrderConfirmedJson.CONFIRMED_AT, 7, 2)))
            .isInstanceOf(OrderConfirmationContractException.class);

        assertThat(activityRepository.count()).isEqualTo(1);
        assertThat(processedRepository.count()).isEqualTo(1);
    }

    @DisplayName("두 소비가 동시에 같은 사실을 처리해도 활동은 한 번만 남는다 (DB 유일 제약). 하나는 RECORDED, 하나는 ALREADY_PROCESSED.")
    @Test
    void concurrentDuplicates_recordOnce() throws Exception {
        String json = OrderConfirmedJson.of(42, 7, 2, 8, 1);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<PurchaseActivityFacade.Result>> results = List.of(
            CompletableFuture.supplyAsync(() -> awaitThen(start, () -> consume(json))),
            CompletableFuture.supplyAsync(() -> awaitThen(start, () -> consume(json))));

        start.countDown();

        assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(RECORDED, ALREADY_PROCESSED);
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(processedRepository.count()).isEqualTo(1);
    }

    private static <T> T awaitThen(CountDownLatch start, Supplier<T> action) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return action.get();
    }

    @DisplayName("조회 뒤 다른 소비가 먼저 저장해 유일 제약 위반이 나면: 그 트랜잭션은 rollback 하고 새 트랜잭션에서 같은 사실임을 확인해 정상 완료한다.")
    @Test
    void uniqueViolationRace_isResolvedInNewTransaction() {
        consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));
        doReturn(List.of()).doCallRealMethod().when(purchaseActivityRepository).findConfirmations(anyString(), anyLong());

        PurchaseActivityFacade.Result result = consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));

        assertThat(result).isEqualTo(ALREADY_PROCESSED);
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(processedRepository.count()).isEqualTo(1);
    }

    @DisplayName("유일 제약 경쟁에서 먼저 저장된 기록이 다른 사실이면 계약 위반으로 드러낸다 (중복으로 삼키지 않는다).")
    @Test
    void uniqueViolationRace_withDifferentFact_isRejected() {
        consume(OrderConfirmedJson.of(42, 7, 2, 8, 1));
        doReturn(List.of()).doCallRealMethod().when(purchaseActivityRepository).findConfirmations(anyString(), anyLong());

        assertThatThrownBy(() -> consume(OrderConfirmedJson.of(42, 7, 5, 8, 1)))
            .isInstanceOf(OrderConfirmationContractException.class);

        assertThat(purchasedQuantity(7L)).isEqualTo(2);
    }

    @DisplayName("두 번째 품목 저장이 실패하면 완료 기록과 앞서 저장한 품목까지 모두 rollback 된다. 원인 예외는 그대로 올라간다.")
    @Test
    void failureOnSecondItem_rollsBackEverything() {
        doAnswer(invocation -> {
            ProductPurchaseActivityModel activity = invocation.getArgument(0);
            if (activity.getProductId() == 8L) {
                throw new QueryTimeoutException("주입된 DB 오류: 두 번째 품목 저장 중");
            }
            return invocation.callRealMethod();
        }).when(purchaseActivityRepository).saveActivity(any());

        assertThatThrownBy(() -> consume(OrderConfirmedJson.of(42, 7, 2, 8, 1)))
            .isInstanceOf(QueryTimeoutException.class);

        assertThat(processedRepository.count()).isZero();
        assertThat(activityRepository.count()).isZero();
    }
}
