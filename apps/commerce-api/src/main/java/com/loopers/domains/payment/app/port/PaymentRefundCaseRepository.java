package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.RefundCase;
import java.util.Optional;

public interface PaymentRefundCaseRepository {
    RefundCase save(RefundCase refundCase);

    Optional<RefundCase> findByOrderId(long orderId);
}
