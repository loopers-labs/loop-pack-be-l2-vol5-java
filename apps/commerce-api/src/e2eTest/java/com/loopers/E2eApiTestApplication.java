package com.loopers;

import com.loopers.config.PurchaseActivityClockConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

// E2E의 api 컨텍스트. CommerceApiApplication과 같은 범위를 스캔하되, 같은 classpath에 올라온 streamer의 컴포넌트(listener·handler·
// 저장 어댑터·앱 클래스)는 컴포넌트 스캔에서 뺀다. 다만 JpaConfig가 엔티티는 com.loopers 전체를, JPA repository는
// com.loopers.infrastructure를 스캔하므로 streamer 엔티티·repository Bean은 이 컨텍스트에도 등록된다. 쓰는 곳이 없어 동작에는 영향이 없고,
// 이 컨텍스트가 ddl-auto create로 구매 활동 테이블까지 만든다.
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan
@ComponentScan(excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.loopers\\.(interfaces|application|infrastructure)\\..*"),
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
        CommerceApiApplication.class, CommerceStreamerApplication.class, PurchaseActivityClockConfig.class})
})
public class E2eApiTestApplication {
}
