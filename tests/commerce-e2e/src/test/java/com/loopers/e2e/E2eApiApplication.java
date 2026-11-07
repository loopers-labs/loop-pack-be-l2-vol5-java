package com.loopers.e2e;

import com.loopers.CommerceApiApplication;
import com.loopers.CommerceStreamerApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * commerce-api context. CommerceApiApplication 과 같은 구성(전체 scan·자동 구성·설정 scan·스케줄링)이되, 같은 classpath 의 commerce-streamer 클래스는 뺀다.
 * api 의 test 출력(FakePaymentGateway·Fixtures)은 scan 된다. @TestConfiguration(테스트별 설정·보존한 W6 경로)은 scan 하지 않고, 필요한 실행만 source 로 넘긴다.
 * 엔티티·JPA repository 는 JpaConfig 가 com.loopers 전체를 scan 하므로 구매 활동 테이블도 이 context 가 만든다 (test 프로필 ddl-auto: create).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan("com.loopers")
@EnableScheduling
@ComponentScan(basePackages = "com.loopers", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class),
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {CommerceApiApplication.class, CommerceStreamerApplication.class}),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
        "com\\.loopers\\.e2e\\..*",
        "com\\.loopers\\.(application|domain|infrastructure)\\.activity\\..*",
        "com\\.loopers\\.interfaces\\.consumer\\..*",
        "com\\.loopers\\.config\\.kafka\\..*"
    })
})
public class E2eApiApplication {
}
