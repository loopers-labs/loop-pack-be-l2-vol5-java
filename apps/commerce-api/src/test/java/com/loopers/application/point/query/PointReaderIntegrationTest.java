package com.loopers.application.point.query;

import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointReaderIntegrationTest {

    @Autowired
    private PointReader pointReader;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;

    @BeforeEach
    void setUp() {
        user = fixtures.userWithBalance(1_000L);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-POINT-02 내 잔액 조회")
    class GetBalance {
        @DisplayName("[FR-POINT-02] 저장된 잔액을 돌려준다.")
        @Test
        void returnsBalance() {
            PointView.Balance info = pointReader.getBalance(user.getId());

            assertThat(info.balance()).isEqualTo(1_000L);
        }

        @DisplayName("[FR-POINT-02 USER_NOT_FOUND]")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> pointReader.getBalance(999L), ErrorType.USER_NOT_FOUND);
        }
    }
}
