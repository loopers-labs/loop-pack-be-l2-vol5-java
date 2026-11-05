package com.loopers.interfaces.consumer;

import com.loopers.confg.kafka.NonRetryableEventException;

public class UnsupportedOrderEventException extends NonRetryableEventException {

    public UnsupportedOrderEventException(String message) {
        super(message);
    }
}
