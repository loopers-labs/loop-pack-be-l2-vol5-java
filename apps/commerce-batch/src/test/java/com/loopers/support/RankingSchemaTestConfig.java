package com.loopers.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/** 원본 구매 테이블과 랭킹 테이블을 테스트 컨테이너에 만든다. batch 는 엔티티가 없어 ddl-auto 가 만들지 않는다 (9주차 D-182, D-183) */
@Configuration
public class RankingSchemaTestConfig {

    @Bean
    public DataSourceInitializer rankingSchemaInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(new ClassPathResource("ranking-schema.sql")));
        return initializer;
    }
}
