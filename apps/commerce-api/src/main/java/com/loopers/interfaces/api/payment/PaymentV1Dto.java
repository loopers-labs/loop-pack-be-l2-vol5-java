package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.interfaces.api.serialization.StrictStringDeserializer;

public class PaymentV1Dto {

    // transactionKey·status는 알림일 뿐 결과 판단에 쓰지 않으므로 없어도 거절하지 않는다.
    public record CallbackRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String providerOrderId,
        String transactionKey,
        String status
    ) {}
}
