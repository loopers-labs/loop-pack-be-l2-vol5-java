package com.loopers.infrastructure.order.support;

import com.loopers.domain.order.OrderConfirmedOutboxModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;

import java.util.HashSet;
import java.util.Set;

/**
 * O11 의 W7 API 기동에만 붙이는 초기화기(발행 W7.4-2). 제품 W7 조립(recorder·Outbox repository·relay·운영·전송)은 그대로 두고
 * 쿠폰 관리 타입과 bean 정의만 뺀다. 그래서 W6→W7 update 가 Outbox 하나만 추가하는지 확인할 수 있다.
 *
 * <p>W6 초기화기를 재사용하면 W7 bean 까지 제거되므로 쓰지 않는다. 제거는 W6 와 같은 이유로 CCPP 뒤 실행되는 bean 정의 BDRPP 가 한다.
 */
public class W7CouponIsolationInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** W6 관리 타입 15개에 Outbox Entity 하나를 더한 범위. */
    public static final Set<String> MANAGED_TYPES = managedTypes();

    static final String FILTER = "w7CouponIsolationManagedClassNameFilter";
    static final String REMOVER = "w7CouponIsolationRemover";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
        registry.registerBeanDefinition(FILTER,
            new RootBeanDefinition(ManagedClassNameFilter.class, () -> (ManagedClassNameFilter) MANAGED_TYPES::contains));
        registry.registerBeanDefinition(REMOVER, new RootBeanDefinition(Remover.class, Remover::new));
    }

    private static Set<String> managedTypes() {
        Set<String> types = new HashSet<>(W6PreparationContextInitializer.MANAGED_TYPES);
        types.add(OrderConfirmedOutboxModel.class.getName());
        return Set.copyOf(types);
    }

    static final class Remover implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            CouponBeanIsolation.remove(registry);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        }
    }
}
