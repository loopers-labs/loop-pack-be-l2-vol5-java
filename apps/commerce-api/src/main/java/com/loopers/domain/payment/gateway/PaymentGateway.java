package com.loopers.domain.payment.gateway;

import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;

/** HTTP 형식이 아니라 관찰 결과만 돌려줌. 상태 전이는 Payment 가 정함 */
public interface PaymentGateway {

    /** 어떤 결과여도 다시 보내지 않음 (D-60) */
    PaymentCreateResult create(PaymentGatewayRequest request);

    /** 거래 키를 알아도 주문 단위 목록으로 조회함 (D-66) */
    PaymentLookupResult findByProviderOrderId(String providerOrderId);
}
