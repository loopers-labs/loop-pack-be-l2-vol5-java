package com.loopers.crossapp;

import com.loopers.CommerceApiApplication;
import com.loopers.CommerceStreamerApplication;
import com.loopers.application.purchase.PurchaseActivityService;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisherConfig;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.purchase.KafkaDeadLetterPublisher;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedEventParser;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * commerce-api 와 commerce-streamer 의 실제 consumer 를 한 application context 에 올리는 테스트 전용 부트스트랩 (7주차 과제 📄 p.6~7).
 * 두 앱이 모두 com.loopers 패키지라 두 @SpringBootApplication 을 그대로 쓰면 설정이 둘 발견되고 component scan 이 서로를 삼킨다.
 * 그래서 이 클래스가 scan 의 시작점이 되어
 * - commerce-api 쪽은 기존처럼 com.loopers 를 scan 하되 두 Application 클래스와 streamer 전용 패키지는 제외하고,
 * - streamer 쪽은 실제로 쓰는 listener factory·listener·parser·service·repository·DLT 발행기 6개만 @Import 로 명시 연결한다.
 * streamer 의 엔티티·JPA 리포지터리는 modules/jpa 의 JpaConfig(@EntityScan("com.loopers"), @EnableJpaRepositories)가 classpath 에서 자동으로 찾는다.
 * Clock 은 두 앱 모두의 빈을 제외하고 테스트가 @MockitoBean 으로 고정값을 넣는다. @EnableScheduling 은 올리지 않는다
 * (스케줄러 조회가 제품의 결과 적용 경로로 먼저 확정해 버리면 W6 경로 재현이 아니게 된다).
 * @TestConfiguration 이 아니라 @Configuration 인 이유: @SpringBootTest(classes=...) 에 @TestConfiguration 만 넘기면 Boot 가 진짜 설정으로
 * 치지 않고 @SpringBootConfiguration 을 다시 찾아 두 Application 클래스를 모두 발견한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ConfigurationPropertiesScan("com.loopers")
@ComponentScan(
    basePackages = "com.loopers",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {CommerceApiApplication.class, CommerceStreamerApplication.class}),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
            "com\\.loopers\\.crossapp\\..*",
            "com\\.loopers\\.application\\.purchase\\..*",
            "com\\.loopers\\.infrastructure\\.purchase\\..*",
            "com\\.loopers\\.interfaces\\.consumer\\..*",
            "com\\.loopers\\.config\\.ClockConfig",
            "com\\.loopers\\.config\\.kafka\\.OrderActivityKafkaConfig"
        })
    }
)
@Import({
    // streamer 에서 실제로 쓰는 6개만 (📄 p.7 "실제로 사용하는 listener factory·parser·handler를 명시적으로 연결")
    OrderActivityKafkaConfig.class,
    OrderConfirmedListener.class,
    OrderConfirmedEventParser.class,
    PurchaseActivityService.class,
    PurchaseActivityRepositoryImpl.class,
    KafkaDeadLetterPublisher.class,
    // commerce-api 의 기존 테스트 fixture (가짜 PG, send 직전 hook 이 있는 가짜 발행기)
    FakePaymentGatewayConfig.class,
    FakeOrderConfirmedPublisherConfig.class,
    // W6 직접 발행 확정 경로의 테스트 전용 보존본
    W6DirectPublishPath.class
})
public class CrossAppTestConfig {
}
