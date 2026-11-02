package com.loopers.domain.purchase;

import java.util.Optional;

public interface OrderConfirmedContractViolationRepository {

    /** 바로 insert한다. 같은 주소가 이미 있으면 이 호출에서 unique 예외가 난다(D-22). */
    OrderConfirmedContractViolation insert(OrderConfirmedContractViolation violation);

    Optional<OrderConfirmedContractViolation> findByAddress(RecordAddress address);
}
