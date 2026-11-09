package com.loopers.e2e;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.PurchaseActivityRecorder;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.interfaces.consumer.OrderConfirmedParser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * E2E context에 commerce-streamer의 실제 구매 활동 consumer만 명시해 연결한다 (docs/week7/design.md 5절 결정 B).
 * streamer 클래스는 {@link ExcludeStreamerFromScan}으로 component scan에서 모두 빠지고, 여기 적은 것만 올라온다.
 * 엔티티·JPA repository는 공통 {@code JpaConfig}의 패키지 scan이 맡는다. listener는 {@code order-event.*} 설정(topic·group·자동 시작)을 따른다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({
    OrderActivityKafkaConfig.class,
    OrderConfirmedParser.class,
    OrderConfirmedListener.class,
    PurchaseActivityHandler.class,
    PurchaseActivityRecorder.class,
    PurchaseActivityRepositoryImpl.class
})
public class PurchaseActivityConsumerConfig {
}
