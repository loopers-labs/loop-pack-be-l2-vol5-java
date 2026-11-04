package com.loopers.infrastructure.resilience.pay;

import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class SystemPaymentRecoveryRuntime implements PaymentRecoveryRuntime {
    @Override public long nanoTime() { return System.nanoTime(); }
    @Override public double randomUnit() { return ThreadLocalRandom.current().nextDouble(); }
    @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
}
