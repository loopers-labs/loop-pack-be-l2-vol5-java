package com.loopers.testcontainers;

import com.loopers.utils.RedisCleanUp;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 모든 Spring 테스트 메서드 전에 Redis를 비운다.
 *
 * <p>고객 상품 조회가 캐시를 쓰면서, 테스트마다 DB를 비워 id가 다시 1부터 쓰이면 이전 테스트가 남긴 product:1 같은 캐시가
 * 새 테스트의 다른 상품으로 읽힌다. 테스트 파일마다 Redis 비우기를 넣는 대신 여기서 한 번에 처리한다.
 * 컨텍스트에 RedisCleanUp 빈이 없으면 아무것도 하지 않는다.
 *
 * <p>등록: src/test/resources/META-INF/spring.factories. 이 키로 등록한 리스너는 spring-test·spring-boot-test가
 * 등록한 기본 리스너에 병합된다(테스트 클래스가 @TestExecutionListeners로 기본값을 덮어쓰지 않는 한).
 * 순서는 기본 리스너(최대 EventPublishingTestExecutionListener 10000)와 겹치지 않는 값으로 둔다 —
 * 의존성 주입(2000)·트랜잭션 시작(4000) 이후, 테스트 클래스의 @BeforeEach보다는 먼저 실행된다.
 */
public class RedisCacheCleanUpListener extends AbstractTestExecutionListener {

    private static final int ORDER = 5_500;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        testContext.getApplicationContext()
            .getBeanProvider(RedisCleanUp.class)
            .ifAvailable(RedisCleanUp::truncateAll);
    }
}
