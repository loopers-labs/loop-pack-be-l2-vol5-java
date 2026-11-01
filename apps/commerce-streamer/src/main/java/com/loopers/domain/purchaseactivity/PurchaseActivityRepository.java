package com.loopers.domain.purchaseactivity;

import java.util.List;

/**
 * 구매 활동 저장 포트. 반환 Entity 는 호출 TX 안에서만 쓴다.
 */
public interface PurchaseActivityRepository {

    /** 같은 사건 ID 또는 같은 주문 ID 의 완료 기록. unique 두 개라 0~2행이다. */
    List<ProcessedOrderConfirmationModel> findProcessedByEventIdOrOrderId(String eventId, long orderId);

    /** 주문의 활동을 상품 ID 오름차순으로 반환한다. */
    List<ProductPurchaseActivityModel> findActivitiesByOrderId(long orderId);

    /** 즉시 flush 해 완료 unique 경쟁을 DB 에서 확정한다. 위반은 Spring 데이터 접근 예외로 전파된다. */
    ProcessedOrderConfirmationModel insertProcessedAndFlush(ProcessedOrderConfirmationModel processed);

    /** 실제 INSERT 를 실행한 뒤 반환한다. */
    ProductPurchaseActivityModel saveActivity(ProductPurchaseActivityModel activity);
}
