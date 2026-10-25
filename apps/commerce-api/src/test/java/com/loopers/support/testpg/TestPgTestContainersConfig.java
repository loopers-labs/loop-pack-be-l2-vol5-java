package com.loopers.support.testpg;

import org.springframework.context.annotation.Configuration;

// MySQL Testcontainers 설정처럼 모든 Spring 테스트 컨텍스트가 같은 로컬 테스트 PG 컨테이너를 쓰게 한다.
// 결제 요청이 로컬 개발용 PG(8082)에 닿지 않고, 컨텍스트마다 주소가 달라 캐시가 갈라지지 않는다.
@Configuration
public class TestPgTestContainersConfig {

    static {
        System.setProperty("payment.pg.base-url", TestPgContainer.baseUrl());
    }
}
