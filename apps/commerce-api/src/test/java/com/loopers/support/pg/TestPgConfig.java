package com.loopers.support.pg;

import org.springframework.context.annotation.Configuration;

/**
 * Spring 통합 테스트가 JVM에 하나뿐인 테스트 PG({@link TestPgServer#shared()})를 바라보게 한다.
 * {@code MySqlTestContainersConfig}처럼 설정 바인딩 전에 시스템 속성으로 주소를 지정한다. 모든 테스트 컨텍스트가 같은 주소를 써서
 * Spring 테스트 컨텍스트 캐시를 그대로 재사용한다 (클래스마다 포트가 다르면 컨텍스트를 새로 띄운다).
 */
@Configuration
public class TestPgConfig {

    static {
        System.setProperty("payment.pg.base-url", TestPgServer.shared().baseUrl());
    }
}
