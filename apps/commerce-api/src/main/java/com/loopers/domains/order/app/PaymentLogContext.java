package com.loopers.domains.order.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;

public final class PaymentLogContext {
    private static final String ORDER_ID = "orderId";
    private static final String ATTEMPT_KEY = "attemptKey";
    private static final String PROVIDER_ORDER_ID = "providerOrderId";
    private static final String TRANSACTION_KEY = "transactionKey";
    private static final List<String> KEYS = List.of(ORDER_ID, ATTEMPT_KEY, PROVIDER_ORDER_ID, TRANSACTION_KEY);

    private PaymentLogContext() {
    }

    public static Scope open(long orderId) {
        Scope scope = new Scope();
        scope.orderId(orderId);
        return scope;
    }

    public static Scope openByProvider(String providerOrderId) {
        Scope scope = new Scope();
        put(PROVIDER_ORDER_ID, providerOrderId);
        return scope;
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }

    // 닫을 때 지우지 않고 이전 값으로 되돌린다. confirm 안에서 결과 반영을 부르듯 진입점이 겹치면 바깥 문맥을 지켜야 하기 때문이다.
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous = new HashMap<>();

        private Scope() {
            for (String key : KEYS) {
                previous.put(key, MDC.get(key));
                MDC.remove(key);
            }
        }

        public void attempt(String attemptKey, String providerOrderId) {
            put(ATTEMPT_KEY, attemptKey);
            put(PROVIDER_ORDER_ID, providerOrderId);
        }

        public void transactionKey(String transactionKey) {
            put(TRANSACTION_KEY, transactionKey);
        }

        public void orderId(long orderId) {
            put(ORDER_ID, String.valueOf(orderId));
        }

        @Override
        public void close() {
            for (String key : KEYS) {
                put(key, previous.get(key));
            }
        }
    }
}
