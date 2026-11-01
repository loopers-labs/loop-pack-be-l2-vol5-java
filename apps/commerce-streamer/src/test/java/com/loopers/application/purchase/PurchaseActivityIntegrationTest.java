package com.loopers.application.purchase;

import com.loopers.application.purchase.dto.PurchaseSaveResult;
import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.domain.purchase.OrderConfirmationConflictException;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.PurchaseLine;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 주문 확정 사실이 몇 번 와도 구매 활동은 한 번만 남고, 다른 사실은 충돌로 드러나는지 DB 를 새로 읽어 확인한다. */
@SpringBootTest
class PurchaseActivityIntegrationTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00Z");

    @Autowired
    private PurchaseActivityFacade facade;

    @Autowired
    private ProductPurchaseActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    private SavePurchaseActivityCommand confirmation(long orderId, PurchaseLine... lines) {
        return confirmation("order-confirmed:v1:" + orderId, orderId, CONFIRMED_AT, lines);
    }

    private SavePurchaseActivityCommand confirmation(String eventId, long orderId, ZonedDateTime confirmedAt, PurchaseLine... lines) {
        return new SavePurchaseActivityCommand(eventId, orderId, confirmedAt, List.of(lines));
    }

    private long activityRows() {
        return jdbcTemplate.queryForObject("select count(*) from product_purchase_activity", Long.class);
    }

    private long confirmationRows() {
        return jdbcTemplate.queryForObject("select count(*) from processed_order_confirmation", Long.class);
    }

    private long quantityOf(long productId) {
        return jdbcTemplate.queryForObject(
            "select coalesce(sum(quantity), 0) from product_purchase_activity where product_id = ?", Long.class, productId
        );
    }

    @DisplayName("주문 하나는 상품별 활동 한 행씩이고, 같은 이벤트를 다시 받아도 늘지 않으며, 다른 주문의 구매는 합산해 읽힌다.")
    @Test
    void savesOncePerProduct_andSumsAcrossOrders() {
        // arrange
        SavePurchaseActivityCommand order42 = confirmation(42, new PurchaseLine(7L, 2), new PurchaseLine(8L, 1));

        // act & assert: 주문 42
        assertThat(facade.save(order42)).isEqualTo(PurchaseSaveResult.SAVED);
        assertThat(activityRows()).isEqualTo(2);

        // act & assert: 같은 이벤트 재수신
        assertThat(facade.save(order42)).isEqualTo(PurchaseSaveResult.DUPLICATE);
        assertThat(activityRows()).isEqualTo(2);

        // act & assert: 별도 주문 43
        assertThat(facade.save(confirmation(43, new PurchaseLine(7L, 3)))).isEqualTo(PurchaseSaveResult.SAVED);
        assertThat(activityRows()).isEqualTo(3);
        assertThat(quantityOf(7L)).isEqualTo(5);
    }

    @DisplayName("저장된 사실과 다른 수량·확정 시각이거나 같은 주문에 다른 eventId 가 오면 충돌로 거절하고, 추가 구매를 만들지 않는다.")
    @ParameterizedTest
    @EnumSource(Conflict.class)
    void rejectsConflictingFact(Conflict conflict) {
        // arrange
        facade.save(confirmation(42, new PurchaseLine(7L, 2), new PurchaseLine(8L, 1)));

        // act & assert
        assertThatThrownBy(() -> facade.save(conflict.command()))
            .isInstanceOf(OrderConfirmationConflictException.class);
        assertThat(activityRows()).isEqualTo(2);
        assertThat(confirmationRows()).isEqualTo(1);
        assertThat(quantityOf(7L)).isEqualTo(2);
    }

    enum Conflict {
        SAME_EVENT_DIFFERENT_QUANTITY("order-confirmed:v1:42", 42L, CONFIRMED_AT, 9),
        SAME_EVENT_DIFFERENT_CONFIRMED_AT("order-confirmed:v1:42", 42L, CONFIRMED_AT.plusMinutes(1), 2),
        SAME_ORDER_DIFFERENT_EVENT("order-confirmed:v1:other", 42L, CONFIRMED_AT, 2);

        private final String eventId;
        private final long orderId;
        private final ZonedDateTime confirmedAt;
        private final int quantityOfProduct7;

        Conflict(String eventId, long orderId, ZonedDateTime confirmedAt, int quantityOfProduct7) {
            this.eventId = eventId;
            this.orderId = orderId;
            this.confirmedAt = confirmedAt;
            this.quantityOfProduct7 = quantityOfProduct7;
        }

        SavePurchaseActivityCommand command() {
            return new SavePurchaseActivityCommand(
                eventId, orderId, confirmedAt, List.of(new PurchaseLine(7L, quantityOfProduct7), new PurchaseLine(8L, 1))
            );
        }
    }

    @DisplayName("같은 이벤트를 동시에 소비해도 한 번만 저장하고, 나머지는 오류 없이 중복으로 끝난다.")
    @Test
    void savesOnce_whenConsumedConcurrently() throws Exception {
        // arrange
        int threads = 6;
        SavePurchaseActivityCommand command = confirmation(42, new PurchaseLine(7L, 2), new PurchaseLine(8L, 1));
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // act
        List<Future<PurchaseSaveResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return facade.save(command);
            }));
        }
        List<PurchaseSaveResult> results = new ArrayList<>();
        for (Future<PurchaseSaveResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // assert
        assertThat(results).filteredOn(PurchaseSaveResult.SAVED::equals).hasSize(1);
        assertThat(results).filteredOn(PurchaseSaveResult.DUPLICATE::equals).hasSize(threads - 1);
        assertThat(activityRows()).isEqualTo(2);
        assertThat(confirmationRows()).isEqualTo(1);
    }

    @DisplayName("품목을 저장하다 실패하면 완료 기록과 앞서 저장한 품목도 함께 되돌린다.")
    @Test
    void rollsBackEverything_whenLaterLineFails() {
        // arrange: 상품 8의 활동이 이미 있어 두 번째 품목에서 유일 제약이 실패한다(완료 기록 없이 남은 행).
        activityRepository.saveAllAndFlush(List.of(new ProductPurchaseActivity(42L, new PurchaseLine(8L, 1), CONFIRMED_AT)));

        // act & assert
        assertThatThrownBy(() -> facade.save(confirmation(42, new PurchaseLine(7L, 2), new PurchaseLine(8L, 1))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(confirmationRows()).isZero();
        assertThat(activityRows()).isEqualTo(1);
        assertThat(quantityOf(7L)).isZero();
    }
}
