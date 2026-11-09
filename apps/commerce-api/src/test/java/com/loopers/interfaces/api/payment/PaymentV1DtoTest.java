package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.interfaces.api.payment.PaymentV1Dto.CallbackRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * callback 본문의 필드 이름을 확인한다. 과제 문장은 알림 본문을 "providerOrderId, transactionKey, status를 전달"한다고 설명하고,
 * PG의 다른 메시지는 같은 값을 {@code orderId}라고 부른다. 어느 이름으로 와도 같은 PG 주문 번호로 읽혀야 한다.
 * 프로젝트 공용 매퍼({@code JacksonConfig})처럼 모르는 필드는 무시하게 둔다. 그래서 별칭이 없으면 이름이 다른 값은 조용히 null이 된다.
 */
class PaymentV1DtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";

    @DisplayName("PG 주문 번호를 orderId 이름으로 받으면, 그대로 읽는다.")
    @Test
    void readsOrderIdName() throws Exception {
        // act
        CallbackRequest request = MAPPER.readValue(
            "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"" + PROVIDER_ORDER_ID + "\",\"status\":\"SUCCESS\"}", CallbackRequest.class);

        // assert
        assertThat(request.transactionKey()).isEqualTo("TEST-TX-0001");
        assertThat(request.orderId()).isEqualTo(PROVIDER_ORDER_ID);
        assertThat(request.status()).isEqualTo("SUCCESS");
    }

    @DisplayName("PG 주문 번호를 providerOrderId 이름으로 받아도, 같은 값으로 읽는다.")
    @Test
    void readsProviderOrderIdName() throws Exception {
        // act
        CallbackRequest request = MAPPER.readValue(
            "{\"transactionKey\":\"TEST-TX-0001\",\"providerOrderId\":\"" + PROVIDER_ORDER_ID + "\",\"status\":\"SUCCESS\"}", CallbackRequest.class);

        // assert
        assertThat(request.transactionKey()).isEqualTo("TEST-TX-0001");
        assertThat(request.orderId()).isEqualTo(PROVIDER_ORDER_ID);
        assertThat(request.status()).isEqualTo("SUCCESS");
    }

    @DisplayName("두 이름이 같은 값으로 함께 오면, 그 값으로 읽는다.")
    @Test
    void readsBothNamesWithSameValue() throws Exception {
        // act
        CallbackRequest request = MAPPER.readValue(
            "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"" + PROVIDER_ORDER_ID + "\",\"providerOrderId\":\"" + PROVIDER_ORDER_ID
                + "\",\"status\":\"SUCCESS\"}", CallbackRequest.class);

        // assert
        assertThat(request.orderId()).isEqualTo(PROVIDER_ORDER_ID);
    }

    @DisplayName("두 이름 어느 쪽도 없으면, PG 주문 번호는 비어 있다. 이 경우는 Facade가 400으로 거절한다.")
    @Test
    void leavesOrderIdEmpty_whenNeitherNameIsPresent() throws Exception {
        // act
        CallbackRequest request = MAPPER.readValue("{\"transactionKey\":\"TEST-TX-0001\",\"status\":\"SUCCESS\"}", CallbackRequest.class);

        // assert
        assertThat(request.orderId()).isNull();
    }
}
