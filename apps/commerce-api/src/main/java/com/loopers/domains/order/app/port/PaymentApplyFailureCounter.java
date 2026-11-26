package com.loopers.domains.order.app.port;

public interface PaymentApplyFailureCounter {
    void record(Source source);

    enum Source {
        CREATE, CALLBACK, MANUAL, AUTOMATIC
    }
}
