package com.loopers.application.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomProviderOrderSuffixGeneratorTest {

    @DisplayName("PG 주문 식별자의 무작위 부분은 소문자 16진수 8자리이고 호출마다 새로 만든다.")
    @Test
    void generatesLowercaseHexSuffix() {
        RandomProviderOrderSuffixGenerator generator = new RandomProviderOrderSuffixGenerator();
        Set<String> generated = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            String suffix = generator.next();
            assertThat(suffix).matches("^[0-9a-f]{8}$");
            generated.add(suffix);
        }

        assertThat(generated).hasSizeGreaterThan(90);
    }
}
