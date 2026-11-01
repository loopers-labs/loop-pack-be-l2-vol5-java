package com.loopers.testsupport;

import com.loopers.domain.order.OrderEventPublisher;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * Spring TestContext 를 거치지 않고 SpringApplicationBuilder 로 직접 띄우면서 PG 확정까지 수행하는 컨텍스트용. @TestBean 이 닿지 않으므로
 * 같은 이름의 실제 adapter 정의를 기록용 포트로 바꾸고, 바꾼 뒤 포트 정의가 그 하나뿐인지 확인한다. bean 덮어쓰기 허용 설정은 쓰지 않는다.
 */
public class OrderEventRecordingInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String PORT_BEAN = "kafkaOrderEventPublisher";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        // 직접 추가한 후처리기는 component scan 보다 먼저 실행되므로, scan 뒤에 실행되도록 bean 정의로 등록한다.
        ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition("orderEventRecordingReplacer",
            new RootBeanDefinition(Replacer.class, Replacer::new));
    }

    static class Replacer implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (!registry.containsBeanDefinition(PORT_BEAN)) {
                throw new IllegalStateException("교체할 실제 주문 발행 포트 정의가 없습니다. [bean = " + PORT_BEAN + "]");
            }
            registry.removeBeanDefinition(PORT_BEAN);
            registry.registerBeanDefinition(PORT_BEAN,
                new RootBeanDefinition(RecordingOrderEventPublisher.class, RecordingOrderEventPublisher::new));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            List<String> ports = List.of(beanFactory.getBeanNamesForType(OrderEventPublisher.class, true, false));
            if (!ports.equals(List.of(PORT_BEAN))) {
                throw new IllegalStateException("주문 발행 포트가 기록용 하나가 아닙니다. [found = " + ports + "]");
            }
        }
    }
}
