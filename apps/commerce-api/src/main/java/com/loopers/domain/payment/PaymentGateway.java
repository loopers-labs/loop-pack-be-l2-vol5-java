package com.loopers.domain.payment;

public interface PaymentGateway {

    CreateObservation create(PaymentCreation creation);
}
