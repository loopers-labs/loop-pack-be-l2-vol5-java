package com.loopers;

import com.loopers.application.purchase.ConsumeResult;
import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.interfaces.consumer.DemoKafkaConsumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

// E2E의 streamer 컨텍스트. 실제 구매 활동 consumer(listener·parser·handler·저장 어댑터)와 공통 모듈 설정만 스캔한다.
// @Configuration을 붙이지 않아 api 컨텍스트의 컴포넌트 스캔에 잡히지 않는다. 스키마는 api 컨텍스트가 만들므로 ddl-auto는 none이다.
// 두 모듈의 application.yml 중 api 쪽이 먼저 읽히므로 이 컨텍스트에 필요한 topic·연결 정보는 명령행 인자(설정 파일보다 우선)로 준다.
@EnableAutoConfiguration
@ComponentScan(
    basePackages = {
        "com.loopers.interfaces.consumer",
        "com.loopers.application.purchase",
        "com.loopers.infrastructure.purchase",
        "com.loopers.config",
        "com.loopers.confg"
    },
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DemoKafkaConsumer.class)
)
@Import(E2eStreamerContext.ConsumeObservation.class)
public class E2eStreamerContext {

    public static ConfigurableApplicationContext start(String topic, String bootstrapServers, String jdbcUrl, String username,
        String password) {
        return new SpringApplicationBuilder(E2eStreamerContext.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.application.name=commerce-streamer-e2e",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--commerce.kafka.topics.order-events=" + topic,
                "--spring.kafka.bootstrap-servers=" + bootstrapServers,
                "--datasource.mysql-jpa.main.jdbc-url=" + jdbcUrl,
                "--datasource.mysql-jpa.main.username=" + username,
                "--datasource.mysql-jpa.main.password=" + password);
    }

    // 실제 handler를 감싸 호출마다 (eventId, offset, 결과)를 남긴다. 처리는 진짜 handler에 위임한다.
    // 수신 횟수(이 기록)와 업무 효과(DB 행)를 따로 세기 위한 관찰 장치다.
    public static class ConsumeObservation {
        public static final List<Consumed> CONSUMED = new CopyOnWriteArrayList<Consumed>();

        public record Consumed(String eventId, long offset, ConsumeResult result) { }

        @Bean
        static BeanPostProcessor purchaseActivityHandlerObserver() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof PurchaseActivityHandler)) {
                        return bean;
                    }
                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.setProxyTargetClass(true);
                    proxyFactory.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        if ("consume".equals(invocation.getMethod().getName())) {
                            OrderConfirmedEvent event = (OrderConfirmedEvent) invocation.getArguments()[0];
                            EventSource source = (EventSource) invocation.getArguments()[1];
                            CONSUMED.add(new Consumed(event.eventId(), source.offset(), (ConsumeResult) result));
                        }
                        return result;
                    });
                    return proxyFactory.getProxy();
                }
            };
        }
    }
}
