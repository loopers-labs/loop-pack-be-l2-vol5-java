package com.loopers.application.pay.gateway;

import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;

/** create는 저장된 발송 권한을 얻은 호출자만 한 번 호출한다. 이 adapter는 발송 권한을 관리하지 않는다. */
public interface PaymentProviderGateway {
    PaymentObservation create(ExpectedPayment expected, CardInput card, PaymentScenario scenario);
    PaymentObservation lookup(ExpectedPayment expected);

    record ExpectedPayment(String providerOrderId, long amount, String transactionKey) {
        public ExpectedPayment {
            if (providerOrderId == null || providerOrderId.isBlank() || providerOrderId.length() < 6
                    || providerOrderId.length() > 100 || amount < 0
                    || (transactionKey != null && (transactionKey.isBlank() || transactionKey.length() > 64))) {
                throw new IllegalArgumentException("저장한 PG 결제 식별·금액이 필요합니다.");
            }
        }
    }

    record CardInput(String cardType, String cardNo) {
        public CardInput {
            if (!"TEST".equals(cardType) || !"0000-0000-0000-0000".equals(cardNo)) {
                throw new IllegalArgumentException("허용한 합성 카드만 사용할 수 있습니다.");
            }
        }

        @Override
        public String toString() {
            return "CardInput[redacted]";
        }
    }
}
