package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** commit 뒤 전송이 실패해도 이미 확정된 주문은 그대로이고 결제를 다시 요청하지 않는다. */
class OrderConfirmedPublishFailureTest extends PaymentFlowTestSupport {

    @MockitoBean
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @DisplayName("전송이 실패해도 알림 응답은 정상이고 주문은 CONFIRMED 로 남으며, PG 에 결제를 다시 요청하지 않는다.")
    @Test
    void keepsConfirmedOrder_whenSendFails() {
        // arrange
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class)))
            .thenThrow(new KafkaException("send failed"));
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2))).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        String transactionKey = pgKeyOf(orderId);
        pg.setStatus(transactionKey, "SUCCESS");

        // act
        var result = paymentResultLookupFacade.lookupPaymentResultOnPgCallback(providerOrderIdOf(orderId), transactionKey);

        // assert
        verify(kafkaTemplate).send(anyString(), anyString(), any(byte[].class));
        assertThat(result.attempt().orderStatus().name()).isEqualTo("CONFIRMED");
        assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(stockOf(shoes)).isEqualTo(3);
    }
}
