package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장부·구매 활동 테이블의 저장과 UNIQUE 제약(설계 D10)을 실제 MySQL 컨테이너로 확인한다.
 */
@SpringBootTest
class PurchaseActivityRepositoryTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00Z");
    private static final ZonedDateTime PROCESSED_AT = ZonedDateTime.parse("2026-09-07T01:00:05Z");

    private final PurchaseActivityRepository purchaseActivityRepository;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PurchaseActivityRepositoryTest(
        PurchaseActivityRepository purchaseActivityRepository,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.purchaseActivityRepository = purchaseActivityRepository;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("장부와 구매 활동을 저장할 때, ")
    @Nested
    class Save {
        @DisplayName("저장 후 flush·clear 하고 다시 조회하면, 저장한 값이 그대로 조회된다.")
        @Transactional
        @Test
        void persistsBothRows_whenReloadedAfterFlushAndClear() {
            // arrange
            purchaseActivityRepository.saveProcessed(
                new ProcessedOrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT, "42|2026-09-07T01:00:00Z|7:2,8:1", PROCESSED_AT));
            purchaseActivityRepository.saveActivity(new ProductPurchaseActivity(42L, 8L, 1, CONFIRMED_AT));
            purchaseActivityRepository.saveActivity(new ProductPurchaseActivity(42L, 7L, 2, CONFIRMED_AT));

            // act
            entityManager.flush();
            entityManager.clear();
            ProcessedOrderConfirmation processed = purchaseActivityRepository.findProcessedByEventId("order-confirmed:v1:42").orElseThrow();
            List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(42L);

            // assert
            assertThat(processed.getOrderId()).isEqualTo(42L);
            assertThat(processed.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT.toInstant());
            assertThat(processed.getFingerprint()).isEqualTo("42|2026-09-07T01:00:00Z|7:2,8:1");
            assertThat(processed.getProcessedAt().toInstant()).isEqualTo(PROCESSED_AT.toInstant());
            assertThat(purchaseActivityRepository.findProcessedByOrderId(42L)).isPresent();
            assertThat(activities).hasSize(2);
            assertThat(activities.get(0).getProductId()).isEqualTo(7L);
            assertThat(activities.get(0).getQuantity()).isEqualTo(2);
            assertThat(activities.get(1).getProductId()).isEqualTo(8L);
            assertThat(activities.get(1).getQuantity()).isEqualTo(1);
            assertThat(activities.get(0).getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT.toInstant());
        }
    }

    @DisplayName("UNIQUE 제약은, ")
    @Nested
    class UniqueConstraints {
        @DisplayName("같은 eventId 의 장부를 두 번 저장하면 uk_poc_event_id 위반으로 거절한다.")
        @Test
        void rejectsDuplicateEventId() {
            purchaseActivityRepository.saveProcessed(new ProcessedOrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT, "fp", PROCESSED_AT));

            assertThatThrownBy(() -> purchaseActivityRepository.saveProcessed(
                new ProcessedOrderConfirmation("order-confirmed:v1:42", 43L, CONFIRMED_AT, "fp", PROCESSED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_poc_event_id");
        }

        @DisplayName("같은 orderId 의 장부를 두 번 저장하면 uk_poc_order_id 위반으로 거절한다.")
        @Test
        void rejectsDuplicateOrderId() {
            purchaseActivityRepository.saveProcessed(new ProcessedOrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT, "fp", PROCESSED_AT));

            assertThatThrownBy(() -> purchaseActivityRepository.saveProcessed(
                new ProcessedOrderConfirmation("other-event", 42L, CONFIRMED_AT, "fp", PROCESSED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_poc_order_id");
        }

        @DisplayName("같은 orderId·productId 의 구매 활동을 두 번 저장하면 uk_ppa_order_product 위반으로 거절한다.")
        @Test
        void rejectsDuplicateOrderProduct() {
            purchaseActivityRepository.saveActivity(new ProductPurchaseActivity(42L, 7L, 2, CONFIRMED_AT));

            assertThatThrownBy(() -> purchaseActivityRepository.saveActivity(new ProductPurchaseActivity(42L, 7L, 3, CONFIRMED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_ppa_order_product");
        }
    }
}
