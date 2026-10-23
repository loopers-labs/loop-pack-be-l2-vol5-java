package com.loopers.application.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;

// UUID 앞 8자리(소문자 16진수)를 쓴다.
@Component
public class RandomProviderOrderSuffixGenerator implements ProviderOrderSuffixGenerator {

    @Override
    public String next() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
