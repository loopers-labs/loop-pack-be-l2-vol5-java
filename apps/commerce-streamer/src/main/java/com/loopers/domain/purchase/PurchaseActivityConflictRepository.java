package com.loopers.domain.purchase;

import java.util.List;

public interface PurchaseActivityConflictRepository {

    /** 바로 insert한다. 같은 주소가 이미 있으면 이 호출에서 unique 예외가 난다(D-22). */
    PurchaseActivityConflict insert(PurchaseActivityConflict conflict);

    List<PurchaseActivityConflict> findAllByOrderId(Long orderId);
}
