package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.LookupJitter;
import java.util.random.RandomGenerator;

public class FullLookupJitter implements LookupJitter {
    private final RandomGenerator randomGenerator;

    public FullLookupJitter(RandomGenerator randomGenerator) {
        this.randomGenerator = randomGenerator;
    }

    @Override
    public long chooseMillis(long maximumMillis) {
        return randomGenerator.nextLong(maximumMillis + 1);
    }
}
