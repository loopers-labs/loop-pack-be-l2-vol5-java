package com.loopers.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/** 공유 쿠폰 테이블을 테스트 컨테이너에 만든다. streamer 는 이 테이블에 엔티티가 없어 ddl-auto 가 만들지 않는다 (8주차 D-172) */
@Configuration
public class CouponSchemaTestConfig {

    @Bean
    public DataSourceInitializer couponSchemaInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(new ClassPathResource("coupon-schema.sql")));
        return initializer;
    }
}
