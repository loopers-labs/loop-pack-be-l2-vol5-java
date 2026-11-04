package com.loopers.infrastructure.order.support;

import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.config.OrderOutboxKafkaConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * W6 직접 발행 경로로 API 컨텍스트를 조립한다(설계 W7.4-1). 제품 profile·운영 스위치가 아니라 테스트가 명시적으로 붙이는 초기화기다.
 *
 * <ol>
 *   <li>refresh 전: 53f912b 의 관리 타입만 허용하는 filter, W6 설정 두 개(@Bean lite 설정), 아래 Replacer 를 bean 정의로 등록한다.</li>
 *   <li>CCPP(PriorityOrdered): 일반 앱 설정·repository 와 W6 설정의 @Bean 정의를 해석한다.</li>
 *   <li>Replacer(순서 없는 BDRPP, CCPP 뒤): W7 Outbox repository·구현·제품 recorder·전송 sender·전용 Kafka 설정 정의를 이름으로
 *   제거하고, 그 설정이 factory 로 만든 producer factory·template 정의도 factoryBeanName 으로 뺀다. 없으면 기동 실패다.</li>
 *   <li>singleton 생성 전: recorder 는 W6 하나, 발행 포트는 W6 adapter 하나인지 확인한다.</li>
 * </ol>
 *
 * <p>Replacer 를 context.addBeanFactoryPostProcessor 로 넣으면 CCPP 보다 먼저 실행돼 repository 정의가 아직 없다. 그래서 bean 정의로 등록한다.
 * 전역 override 허용·@Primary·이름 접두사 일괄 삭제는 쓰지 않는다. 순수 encoder·verifier 는 전송을 하지 않으므로 남긴다.
 * relay·scheduler·복구 서비스는 아직 제품에 없다. 후속 단위가 그 정의를 추가하면 {@link #W7_DEFINITIONS} 에 이름을 더하고,
 * 남은 W7 정의는 기동 뒤 부재 단언으로 드러난다.
 */
public class W6PreparationContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 53f912b 의 관리 타입. Entity 12개·Embeddable 2개·MappedSuperclass 1개다. Outbox 는 관리·DDL 대상이 아니다. */
    public static final Set<String> MANAGED_TYPES = Set.of(
        "com.loopers.domain.brand.BrandModel",
        "com.loopers.domain.example.ExampleModel",
        "com.loopers.domain.like.LikeModel",
        "com.loopers.domain.order.OrderModel",
        "com.loopers.domain.order.OrderItemModel",
        "com.loopers.domain.payment.PaymentModel",
        "com.loopers.domain.payment.PaymentConflictModel",
        "com.loopers.domain.point.PointModel",
        "com.loopers.domain.point.PointHistoryModel",
        "com.loopers.domain.product.ProductModel",
        "com.loopers.domain.product.StockHistoryModel",
        "com.loopers.domain.user.UserModel",
        "com.loopers.domain.common.Money",
        "com.loopers.domain.product.Stock",
        "com.loopers.domain.BaseEntity");

    /** CCPP 뒤 제거할 W7 정의. 이름이 하나라도 없으면 조립이 바뀐 것이므로 기동을 거절한다. */
    public static final List<String> W7_DEFINITIONS = List.of(
        "orderConfirmedOutboxJpaRepository",
        "orderConfirmedOutboxRepositoryImpl",
        "orderOutboxRecordingService",
        "kafkaOrderOutboxSender",
        "orderOutboxKafkaConfig");

    /** 위 설정의 @Bean 이 만든 정의. factoryBeanName 연계로 제거되어야 하며, 남거나 이름이 바뀌면 기동을 거절한다. */
    public static final List<String> W7_PRODUCED_DEFINITIONS = List.of(
        OrderOutboxKafkaConfig.ORDER_OUTBOX_PRODUCER_FACTORY,
        OrderOutboxKafkaConfig.ORDER_OUTBOX_KAFKA_TEMPLATE);

    static final String FILTER = "w6ManagedClassNameFilter";
    static final String TEST_CONFIGURATION = "w6OrderEventTestConfiguration";
    static final String KAFKA_CONFIGURATION = "w6OrderEventKafkaConfig";
    static final String REPLACER = "w6PreparationReplacer";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
        registry.registerBeanDefinition(FILTER,
            new RootBeanDefinition(ManagedClassNameFilter.class, () -> (ManagedClassNameFilter) MANAGED_TYPES::contains));
        registry.registerBeanDefinition(TEST_CONFIGURATION, new AnnotatedGenericBeanDefinition(W6OrderEventTestConfiguration.class));
        registry.registerBeanDefinition(KAFKA_CONFIGURATION, new AnnotatedGenericBeanDefinition(W6OrderEventKafkaConfig.class));
        registry.registerBeanDefinition(REPLACER, new RootBeanDefinition(Replacer.class, Replacer::new));
    }

    static final class Replacer implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            for (String name : W7_DEFINITIONS) {
                if (!registry.containsBeanDefinition(name)) {
                    throw new IllegalStateException("제거할 W7 bean 정의가 없습니다. [bean = " + name + "]");
                }
            }
            for (String name : W7_DEFINITIONS) {
                registry.removeBeanDefinition(name);
            }
            // 제거한 bean 을 factory 로 쓰던 @Bean 정의도 함께 뺀다. 남겨 두면 생성 시 실패로 드러난다.
            List<String> produced = new ArrayList<>();
            for (String name : registry.getBeanDefinitionNames()) {
                BeanDefinition definition = registry.getBeanDefinition(name);
                if (definition.getFactoryBeanName() != null && W7_DEFINITIONS.contains(definition.getFactoryBeanName())) {
                    produced.add(name);
                }
            }
            produced.forEach(registry::removeBeanDefinition);
            if (!produced.stream().sorted().toList().equals(W7_PRODUCED_DEFINITIONS.stream().sorted().toList())) {
                throw new IllegalStateException("W7 설정이 만든 정의가 예상과 다릅니다. [removed = " + produced + "]");
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            List<String> recorders = Arrays.asList(beanFactory.getBeanNamesForType(OrderConfirmedRecorder.class, true, false));
            List<String> ports = Arrays.asList(beanFactory.getBeanNamesForType(W6OrderEventPublisher.class, true, false));
            if (!recorders.equals(List.of(W6OrderEventTestConfiguration.RECORDER))
                || !ports.equals(List.of(W6OrderEventTestConfiguration.PORT))) {
                throw new IllegalStateException("W6 조립이 아닙니다. [recorders = " + recorders + ", ports = " + ports + "]");
            }
        }
    }
}
