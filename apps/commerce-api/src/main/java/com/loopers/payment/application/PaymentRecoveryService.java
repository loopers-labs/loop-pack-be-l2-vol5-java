package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class PaymentRecoveryService implements PaymentRecoveryUseCase {

    @Override
    public StaleDispatchResult closeStaleDispatches(ZonedDateTime now) {
        return new StaleDispatchResult(0, 0);
    }

    @Override
    public int reconcileDue(ZonedDateTime now) {
        return 0;
    }
}
