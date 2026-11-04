package com.loopers.infrastructure.persistence.pay.entity;

import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class PaymentScenarioFields {
    @Column(name = "reject_before_create", nullable = false)
    boolean rejectBeforeCreate;
    @Column(name = "final_status", nullable = false, length = 16)
    String finalStatus;
    @Column(name = "processing_delay_ms", nullable = false)
    long processingDelayMs;
    @Column(name = "create_response_mode", nullable = false, length = 16)
    String createResponseMode;
    @Column(name = "create_response_delay_ms", nullable = false)
    long createResponseDelayMs;
    @Column(name = "lookup_response_mode", nullable = false, length = 16)
    String lookupResponseMode;
    @Column(name = "lookup_response_delay_ms", nullable = false)
    long lookupResponseDelayMs;
    @Column(name = "callback_mode", nullable = false, length = 16)
    String callbackMode;
    @Column(name = "callback_delay_ms", nullable = false)
    long callbackDelayMs;

    protected PaymentScenarioFields() {}

    static PaymentScenarioFields from(PaymentScenario scenario) {
        PaymentScenarioFields fields = new PaymentScenarioFields();
        fields.rejectBeforeCreate = scenario.rejectBeforeCreate();
        fields.finalStatus = scenario.finalStatus().name();
        fields.processingDelayMs = scenario.processingDelayMs();
        fields.createResponseMode = scenario.createResponseMode().name();
        fields.createResponseDelayMs = scenario.createResponseDelayMs();
        fields.lookupResponseMode = scenario.lookupResponseMode().name();
        fields.lookupResponseDelayMs = scenario.lookupResponseDelayMs();
        fields.callbackMode = scenario.callbackMode().name();
        fields.callbackDelayMs = scenario.callbackDelayMs();
        return fields;
    }

    PaymentScenario toDomain() {
        return new PaymentScenario(rejectBeforeCreate, ProviderPaymentStatus.valueOf(finalStatus), processingDelayMs,
                PaymentScenario.ResponseMode.valueOf(createResponseMode), createResponseDelayMs,
                PaymentScenario.ResponseMode.valueOf(lookupResponseMode), lookupResponseDelayMs,
                PaymentScenario.CallbackMode.valueOf(callbackMode), callbackDelayMs);
    }
}
