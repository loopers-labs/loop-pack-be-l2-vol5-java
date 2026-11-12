package com.loopers.application.order;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

/** 같은 비교를 테스트 프로필 기본값(SQL을 콘솔에 찍음)으로 다시 잰다. 앞선 처리량 실험이 이 조건이었다. */
@Tag("experiment")
@SpringBootTest(properties = {"commerce.order-events.enabled=true", "spring.jpa.show-sql=true"})
class OutboxRelayStructureWithSqlLogExperimentTest extends OutboxRelayStructureExperimentTest {
}
