package com.loopers.e2e;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * commerce-batch context. CommerceBatchApplication 처럼 batch 패키지와 공용 JPA 설정(데이터소스·트랜잭션)만 가져온다.
 * Boot 의 JDBC JobRepository·JobLauncher·transactionManager 는 자동 구성 그대로다. 테이블은 만들지 않는다 (api context·배포 SQL 이 만든다).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan("com.loopers.batch")
@ComponentScan(basePackages = {"com.loopers.config.jpa", "com.loopers.batch"})
public class E2eBatchApplication {
}
