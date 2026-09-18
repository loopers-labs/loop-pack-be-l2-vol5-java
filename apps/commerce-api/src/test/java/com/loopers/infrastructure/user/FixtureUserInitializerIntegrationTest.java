package com.loopers.infrastructure.user;

import com.loopers.application.user.PointService;
import com.loopers.domain.user.UserIdentity;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixtureUserInitializerIntegrationTest {

    @Autowired
    private FixtureUserIdentityRepository identityRepository;

    @Autowired
    private FixtureUserInitializer initializer;

    @Autowired
    private PointService pointService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POINT-FIXTURE-01: 단일 식별 fixture의 ID만 잔액 0으로 초기화한다.")
    @Test
    void initializesAllMappedUsersWithZeroBalance() {
        initializer.initialize();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM `user` ORDER BY id", Long.class))
            .containsExactlyElementsOf(identityRepository.identities().stream().map(UserIdentity::userId).sorted().toList());
        assertThat(jdbcTemplate.queryForList("SELECT point_balance FROM `user` ORDER BY id", Long.class))
            .containsOnly(0L);
    }

    @DisplayName("POINT-FIXTURE-02: 재초기화는 기존 잔액과 감사 시각을 보존하고 누락 행만 추가한다.")
    @Test
    void initializesOnlyMissingUsersAndPreservesStoredBalances() {
        initializer.initialize();
        pointService.charge("alice", 5000L);
        jdbcTemplate.update("DELETE FROM `user` WHERE id = 2");
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` WHERE id = 1");

        initializer.initialize();

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` WHERE id = 1")).isEqualTo(before);
        assertThat(pointService.balance("alice").balance()).isEqualTo(5000L);
        assertThat(pointService.balance("bob").balance()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user`", Long.class))
            .isEqualTo(identityRepository.identities().size());
    }
}
