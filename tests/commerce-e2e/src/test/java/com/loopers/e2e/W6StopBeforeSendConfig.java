package com.loopers.e2e;

import com.loopers.support.w6.W6KafkaOrderConfirmedPublisher;
import com.loopers.support.w6.W6OrderConfirmedPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W6 직접 발행 앞의 중단 hook. 지정한 주문은 확정 commit 뒤 W6 listener 가 send 를 부르기 직전에 멈춘다 (W6 의 미발행 사례와 같은 위치).
 * W6 listener 는 예외를 삼키므로 확정 응답은 CONFIRMED 그대로다. 나머지 주문은 실제 W6 publisher 로 보낸다.
 */
@Configuration
public class W6StopBeforeSendConfig {
    public static final Set<Long> STOP_BEFORE_SEND = ConcurrentHashMap.newKeySet();

    @Bean
    @Primary
    W6OrderConfirmedPublisher stopBeforeSendPublisher(W6KafkaOrderConfirmedPublisher delegate) {
        return event -> {
            if (STOP_BEFORE_SEND.contains(event.orderId())) {
                throw new IllegalStateException("주입된 중단: 주문 확정 commit 뒤, Kafka send 직전 [orderId = " + event.orderId() + "]");
            }
            return delegate.publish(event);
        };
    }
}
