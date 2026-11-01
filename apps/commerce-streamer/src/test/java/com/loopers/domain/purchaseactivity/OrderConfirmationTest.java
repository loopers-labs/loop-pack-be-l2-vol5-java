package com.loopers.domain.purchaseactivity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;

/** C1·C3: 불변 사실의 정규화와 저장 사실 비교의 분류. DB 없이 Entity 생성 factory 만 쓴다. 손상 저장은 DB 검증에서 본다. */
@DisplayName("OrderConfirmation 은 원본 품목을 검사 후 합산·정렬하고, 저장 정합성을 먼저 본 뒤 같은 사실인지 비교한다.")
class OrderConfirmationTest {

    private static final ZonedDateTime AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC);

    private static OrderConfirmation event(long orderId, ZonedDateTime at, OrderConfirmation.Item... items) {
        return OrderConfirmation.normalize("OrderConfirmed", 1, "order-confirmed:" + orderId, orderId, at, List.of(items));
    }

    private static OrderConfirmation.Item item(long productId, long quantity) {
        return new OrderConfirmation.Item(productId, quantity);
    }

    private static List<ProductPurchaseActivityModel> activities(OrderConfirmation event) {
        return event.items().stream().map(item -> ProductPurchaseActivityModel.purchased(event, item)).toList();
    }

    private static PurchaseActivityErrorCode codeOf(Runnable action) {
        PurchaseActivityException error = catchThrowableOfType(PurchaseActivityException.class, action::run);
        assertThat(error).as("거절되어야 한다").isNotNull();
        return error.getCode();
    }

    @DisplayName("같은 상품은 합산하고 상품 ID 오름차순 불변 목록으로 만든다.")
    @Test
    void normalizes() {
        OrderConfirmation event = event(42, AT, item(8, 1), item(7, 1), item(7, 1));

        assertAll(
            () -> assertThat(event.items()).containsExactly(item(7, 2), item(8, 1)),
            () -> assertThat(event.items().getClass().getName()).startsWith("java.util.ImmutableCollections")
        );
    }

    @DisplayName("원본 항목의 0·음수, 합산 overflow, 빈 품목은 INVALID_EVENT 다.")
    @Test
    void rejectsInvalidItems() {
        assertAll(
            () -> assertThat(codeOf(() -> item(7, -1))).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> item(0, 1))).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> event(42, AT, item(7, Long.MAX_VALUE), item(7, 1))))
                .isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> event(42, AT))).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT)
        );
    }

    @DisplayName("UTC 마이크로초가 아닌 시각·지원하지 않는 유형/버전·정규화되지 않은 품목은 INVALID_EVENT, 유도 규칙 위반 ID 는 IDENTITY_CONFLICT 다.")
    @Test
    void guardsInvariants() {
        assertAll(
            () -> assertThat(codeOf(() -> event(42, AT.plusNanos(1), item(7, 1)))).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> event(42, AT.withZoneSameInstant(ZoneId.of("Asia/Seoul")), item(7, 1))))
                .isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> event(42, AT.withZoneSameInstant(ZoneId.of("Europe/London")), item(7, 1))))
                .isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> new OrderConfirmation("OrderConfirmed", 2, "order-confirmed:42", 42, AT, List.of(item(7, 1)))))
                .isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> new OrderConfirmation("OrderConfirmed", 1, "order-confirmed:42", 42, AT,
                List.of(item(8, 1), item(7, 1))))).isEqualTo(PurchaseActivityErrorCode.INVALID_EVENT),
            () -> assertThat(codeOf(() -> new OrderConfirmation("OrderConfirmed", 1, "order-confirmed:43", 42, AT, List.of(item(7, 1)))))
                .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT)
        );
    }

    @DisplayName("정상 저장과 같은 사실이면 통과하고, 활동 조회 순서는 비교에 영향을 주지 않는다.")
    @Test
    void sameFact() {
        OrderConfirmation stored = event(42, AT, item(7, 2), item(8, 1));
        ProcessedOrderConfirmationModel processed = ProcessedOrderConfirmationModel.completed(stored);
        List<ProductPurchaseActivityModel> reversed = activities(stored).reversed();

        assertAll(
            () -> assertThatCode(() -> event(42, AT, item(8, 1), item(7, 1), item(7, 1)).requireSameFact(processed, activities(stored)))
                .doesNotThrowAnyException(),
            () -> assertThatCode(() -> stored.requireSameFact(processed, reversed)).doesNotThrowAnyException()
        );
    }

    @DisplayName("정상 저장과 수량·상품 집합·확정 시각이 다르면 FACT_CONFLICT 다.")
    @Test
    void factConflict() {
        OrderConfirmation stored = event(42, AT, item(7, 2), item(8, 1));
        ProcessedOrderConfirmationModel processed = ProcessedOrderConfirmationModel.completed(stored);
        List<ProductPurchaseActivityModel> rows = activities(stored);

        assertAll(
            () -> assertThat(codeOf(() -> event(42, AT, item(7, 3), item(8, 1)).requireSameFact(processed, rows)))
                .isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(codeOf(() -> event(42, AT, item(7, 2)).requireSameFact(processed, rows)))
                .isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(codeOf(() -> event(42, AT, item(7, 2), item(8, 1), item(9, 1)).requireSameFact(processed, rows)))
                .isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(codeOf(() -> event(42, AT.plusNanos(1_000), item(7, 2), item(8, 1)).requireSameFact(processed, rows)))
                .isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT)
        );
    }

    @DisplayName("정상 저장의 다른 주문과 비교하면 IDENTITY_CONFLICT 다(조회 조건상 DB 에서는 도달하지 않는 방어 분기).")
    @Test
    void identityConflictDefensiveBranch() {
        OrderConfirmation stored = event(42, AT, item(7, 2));
        assertThat(codeOf(() -> event(43, AT, item(7, 2))
            .requireSameFact(ProcessedOrderConfirmationModel.completed(stored), activities(stored))))
            .isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT);
    }

    @DisplayName("활동 누락·다른 주문의 활동·다른 시각의 활동은 incoming 이 달라도 먼저 STORAGE_INTEGRITY_ERROR 다.")
    @Test
    void storageIntegrityFirst() {
        OrderConfirmation stored = event(42, AT, item(7, 2), item(8, 1));
        ProcessedOrderConfirmationModel processed = ProcessedOrderConfirmationModel.completed(stored);
        OrderConfirmation different = event(42, AT, item(7, 5));
        OrderConfirmation otherOrder = event(43, AT, item(8, 1));
        OrderConfirmation otherTime = event(42, AT.plusSeconds(1), item(8, 1));
        ProductPurchaseActivityModel first = ProductPurchaseActivityModel.purchased(stored, item(7, 2));

        assertAll(
            () -> assertThat(codeOf(() -> different.requireSameFact(processed, List.of(first))))
                .isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR),
            () -> assertThat(codeOf(() -> different.requireSameFact(processed,
                List.of(first, ProductPurchaseActivityModel.purchased(otherOrder, item(8, 1))))))
                .isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR),
            () -> assertThat(codeOf(() -> different.requireSameFact(processed,
                List.of(first, ProductPurchaseActivityModel.purchased(otherTime, item(8, 1))))))
                .isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR),
            () -> assertThat(codeOf(() -> different.requireSameFact(processed, List.of(first, first))))
                .isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR)
        );
    }
}
