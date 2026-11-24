package com.loopers.domains.order.app;

public final class PaymentLogContext {
    private PaymentLogContext() {
    }

    public static Scope open(long orderId) {
        return new Scope();
    }

    public static final class Scope implements AutoCloseable {
        private Scope() {
        }

        public void attempt(String attemptKey, String providerOrderId) {
        }

        public void transactionKey(String transactionKey) {
        }

        public void orderId(long orderId) {
        }

        @Override
        public void close() {
        }
    }
}
