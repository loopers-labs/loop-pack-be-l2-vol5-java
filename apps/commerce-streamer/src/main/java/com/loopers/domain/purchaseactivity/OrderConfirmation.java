package com.loopers.domain.purchaseactivity;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode.FACT_CONFLICT;
import static com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode.IDENTITY_CONFLICT;
import static com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode.INVALID_EVENT;
import static com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR;

/**
 * 소비한 OrderConfirmed v1 의 업무 사실(소비 설계 2·4절). items 는 상품 ID 오름차순·상품별 한 원소로 정규화된 불변 목록이다.
 * commerce-api 의 사건 클래스를 공유하지 않고 wire 계약만 따른다.
 */
public record OrderConfirmation(String eventType, int schemaVersion, String eventId, long orderId,
                                ZonedDateTime confirmedAt, List<Item> items) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVENT_ID_LENGTH = 64;

    private static final String EVENT_ID_PREFIX = "order-confirmed:";

    public OrderConfirmation {
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
            throw new PurchaseActivityException(INVALID_EVENT, "지원하지 않는 이벤트 유형·버전입니다.");
        }
        if (orderId < 1L || eventId == null || !isUtcMicros(confirmedAt) || items == null || items.isEmpty()) {
            throw new PurchaseActivityException(INVALID_EVENT, "주문 ID·사건 ID·확정 시각·품목이 올바르지 않습니다.");
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).productId() >= items.get(i).productId()) {
                throw new PurchaseActivityException(INVALID_EVENT, "품목이 상품 ID 오름차순으로 정규화되지 않았습니다.");
            }
        }
        if (!eventId.equals(eventIdOf(orderId))) {
            throw new PurchaseActivityException(IDENTITY_CONFLICT, "사건 ID 가 주문 ID 에서 유도한 값과 다릅니다.");
        }
        items = List.copyOf(items);
    }

    /**
     * 원본 품목을 상품별로 합산·정렬한다. 원본 항목의 양수 검사는 Item 생성에서 이미 끝났다.
     * 합계만 검사하면 같은 상품의 -1 과 3 이 정상 수량 2 로 가려지기 때문이다.
     */
    public static OrderConfirmation normalize(String eventType, int schemaVersion, String eventId, long orderId,
                                              ZonedDateTime confirmedAt, List<Item> originalItems) {
        if (originalItems == null || originalItems.isEmpty()) {
            throw new PurchaseActivityException(INVALID_EVENT, "품목이 비어 있습니다.");
        }
        Map<Long, Long> quantities = new TreeMap<>();
        try {
            for (Item item : originalItems) {
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            throw new PurchaseActivityException(INVALID_EVENT, "상품별 수량 합계가 표현 범위를 넘습니다.");
        }
        List<Item> items = quantities.entrySet().stream()
            .map(entry -> new Item(entry.getKey(), entry.getValue()))
            .toList();
        return new OrderConfirmation(eventType, schemaVersion, eventId, orderId, confirmedAt, items);
    }

    public static String eventIdOf(long orderId) {
        return EVENT_ID_PREFIX + Long.toString(orderId);
    }

    /** null 은 false. 지금 offset 만 0 인 지역 zone 은 UTC 가 아니다. */
    public static boolean isUtcMicros(ZonedDateTime value) {
        return value != null
            && value.getZone().normalized().equals(ZoneOffset.UTC)
            && value.getNano() % 1_000 == 0;
    }

    /**
     * 저장된 사건과 같은 사실인지 확인한다. 저장 상태의 정합성을 먼저 검사하고(손상이면 STORAGE_INTEGRITY_ERROR),
     * 정상일 때만 식별자(IDENTITY_CONFLICT)와 고정 필드·시각·전체 품목(FACT_CONFLICT)을 비교한다. 같으면 정상 반환한다.
     */
    public void requireSameFact(ProcessedOrderConfirmationModel stored, List<ProductPurchaseActivityModel> activities) {
        OrderConfirmation storedFact = restore(stored, activities);
        if (!eventId.equals(storedFact.eventId()) || orderId != storedFact.orderId()) {
            throw new PurchaseActivityException(IDENTITY_CONFLICT, "저장된 사건과 식별자가 다릅니다.");
        }
        if (!eventType.equals(storedFact.eventType())
            || schemaVersion != storedFact.schemaVersion()
            || !confirmedAt.toInstant().equals(storedFact.confirmedAt().toInstant())
            || !items.equals(storedFact.items())) {
            throw new PurchaseActivityException(FACT_CONFLICT, "저장된 사건과 업무 사실이 다릅니다.");
        }
    }

    /** 저장 행에서 사실을 되살린다. 손상은 입력 충돌이 아니므로 생성자 검사에 맡기지 않고 먼저 STORAGE_INTEGRITY_ERROR 로 거절한다. */
    private static OrderConfirmation restore(ProcessedOrderConfirmationModel stored,
                                             List<ProductPurchaseActivityModel> activities) {
        Long orderId = stored.getOrderId();
        Integer itemCount = stored.getItemCount();
        ZonedDateTime confirmedAt = stored.getConfirmedAt();
        if (orderId == null || orderId < 1L
            || !eventIdOf(orderId).equals(stored.getEventId())
            || !EVENT_TYPE.equals(stored.getEventType())
            || stored.getSchemaVersion() == null || stored.getSchemaVersion() != SCHEMA_VERSION
            || !isUtcMicros(confirmedAt)
            || itemCount == null || itemCount < 1) {
            throw storageError("저장된 완료 기록이 계약을 지키지 않습니다.");
        }
        if (activities.size() != itemCount) {
            throw storageError("저장된 활동 수가 완료 기록의 품목 수와 다릅니다.");
        }
        List<ProductPurchaseActivityModel> sorted = new ArrayList<>(activities);
        sorted.sort(Comparator.comparing(ProductPurchaseActivityModel::getProductId,
            Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Item> items = new ArrayList<>(sorted.size());
        for (ProductPurchaseActivityModel activity : sorted) {
            Long productId = activity.getProductId();
            Long quantity = activity.getQuantity();
            ZonedDateTime activityAt = activity.getConfirmedAt();
            if (!orderId.equals(activity.getOrderId())
                || productId == null || productId < 1L
                || quantity == null || quantity < 1L
                || activityAt == null || !activityAt.toInstant().equals(confirmedAt.toInstant())
                || (!items.isEmpty() && items.get(items.size() - 1).productId() == productId)) {
                throw storageError("저장된 활동이 완료 기록과 맞지 않습니다.");
            }
            items.add(new Item(productId, quantity));
        }
        return new OrderConfirmation(stored.getEventType(), stored.getSchemaVersion(), stored.getEventId(), orderId,
            confirmedAt, items);
    }

    private static PurchaseActivityException storageError(String message) {
        return new PurchaseActivityException(STORAGE_INTEGRITY_ERROR, message);
    }

    public record Item(long productId, long quantity) {

        public Item {
            if (productId < 1L || quantity < 1L) {
                throw new PurchaseActivityException(INVALID_EVENT, "품목의 상품 ID·수량은 양수여야 합니다.");
            }
        }
    }
}
