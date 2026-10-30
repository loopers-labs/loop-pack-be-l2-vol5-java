package com.loopers.domain.activity;

import java.util.List;

public interface PurchaseActivityRepository {

    /** eventId 또는 orderId 가 같은 완료 기록. 정상이면 0건 또는 1건. */
    List<ProcessedOrderConfirmationModel> findConfirmations(String eventId, Long orderId);

    /** 저장하고 바로 flush 한다. 유일 제약 위반은 이 호출에서 DataIntegrityViolationException 으로 드러난다. */
    void saveConfirmation(ProcessedOrderConfirmationModel confirmation);

    /** 저장하고 바로 flush 한다. */
    void saveActivity(ProductPurchaseActivityModel activity);

    List<ProductPurchaseActivityModel> findActivitiesByOrderId(Long orderId);
}
