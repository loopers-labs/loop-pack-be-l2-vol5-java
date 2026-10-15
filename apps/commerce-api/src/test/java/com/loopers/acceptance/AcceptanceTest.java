package com.loopers.acceptance;

import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * 인수 테스트 공통 기반 — 실제 포트로 띄운 애플리케이션에 HTTP 요청을 보내 사용자가 관찰하는 결과만 검증한다.
 *
 * <p>요청을 하나씩 순서대로 보내므로 "변경이 끝난 뒤 새로 시작한 조회"만 다룬다. rollback·캐시 무효화 실패·늦은 재삽입·
 * Redis 장애처럼 순서를 통제하거나 내부를 봐야 하는 장면은 통합 테스트의 몫이다 (docs/week4/design.md 3번 섹션).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AcceptanceTestSecurityConfig.class)
abstract class AcceptanceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUpPort() {
        RestAssured.port = port;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 사용자 생성 API는 범위 밖이라(2주차: 회원가입 없음) 저장소로 직접 만든다. */
    protected Long givenUser() {
        return userJpaRepository.save(new UserModel()).getId();
    }
}
