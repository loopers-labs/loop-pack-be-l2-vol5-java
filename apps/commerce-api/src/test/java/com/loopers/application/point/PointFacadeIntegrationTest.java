package com.loopers.application.point;

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
class PointFacadeIntegrationTest {

    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        user = fixtures.userWithBalance(1_000L);
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-POINT-01 포인트 충전")
    class Charge {
        @DisplayName("[FR-POINT-01][INV-01][INV-02] 잔액에 더해져 저장되고 충전 후 잔액을 돌려준다.")
        @Test
        void chargesBalance() {
            PointInfo info = pointFacade.charge(user.getId(), 500L);

            assertThat(info.userId()).isEqualTo(user.getId());
            assertThat(info.balance()).isEqualTo(1_500L);
            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_500L);
        }

        @DisplayName("[FR-POINT-01 INVALID_AMOUNT] 누락·0·음수는 잔액 유지.")
        @Test
        void throwsInvalidAmount_keepsBalance() {
            assertThrowsErrorType(() -> pointFacade.charge(user.getId(), null), ErrorType.INVALID_AMOUNT);
            assertThrowsErrorType(() -> pointFacade.charge(user.getId(), 0L), ErrorType.INVALID_AMOUNT);
            assertThrowsErrorType(() -> pointFacade.charge(user.getId(), -1L), ErrorType.INVALID_AMOUNT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[FR-POINT-01 BALANCE_LIMIT_EXCEEDED] 잔액 + amount 가 표현 범위 초과면 잔액 유지 (롤백).")
        @Test
        void throwsBalanceLimitExceeded_keepsBalance() {
            UserModel rich = fixtures.userWithBalance(Long.MAX_VALUE);

            assertThrowsErrorType(() -> pointFacade.charge(rich.getId(), 1L), ErrorType.BALANCE_LIMIT_EXCEEDED);

            assertThat(fixtures.balanceOf(rich.getId())).isEqualTo(Long.MAX_VALUE);
        }

        @DisplayName("[FR-POINT-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> pointFacade.charge(999L, 100L), ErrorType.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-POINT-02 내 잔액 조회")
    class GetBalance {
        @DisplayName("[FR-POINT-02] 저장된 잔액을 돌려준다.")
        @Test
        void returnsBalance() {
            PointInfo info = pointFacade.getBalance(user.getId());

            assertThat(info.balance()).isEqualTo(1_000L);
        }

        @DisplayName("[FR-POINT-02 USER_NOT_FOUND]")
        @Test
        void throwsUserNotFound() {
            assertThrowsErrorType(() -> pointFacade.getBalance(999L), ErrorType.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-POINT-03 포인트 환불 [추가]")
    class Refund {
        @DisplayName("[FR-POINT-03][INV-01] 잔액에서 빠져 저장된다. 전액 환불로 0 까지 가능.")
        @Test
        void refundsBalance() {
            PointInfo info = pointFacade.refund(user.getId(), 1_000L);

            assertThat(info.balance()).isZero();
            assertThat(fixtures.balanceOf(user.getId())).isZero();
        }

        @DisplayName("[FR-POINT-03 INVALID_AMOUNT] 누락·0·음수는 잔액 유지.")
        @Test
        void throwsInvalidAmount() {
            assertThrowsErrorType(() -> pointFacade.refund(user.getId(), 0L), ErrorType.INVALID_AMOUNT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[FR-POINT-03 INSUFFICIENT_POINT] 잔액 < amount 면 잔액 유지.")
        @Test
        void throwsInsufficientPoint() {
            assertThrowsErrorType(() -> pointFacade.refund(user.getId(), 1_001L), ErrorType.INSUFFICIENT_POINT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-POINT-01 사용자 포인트 충전 [추가]")
    class ChargeByAdmin {
        @DisplayName("[FR-ADMIN-POINT-01] 대상 사용자의 잔액에 더해진다. 관리자 자신의 잔액은 그대로.")
        @Test
        void chargesTargetUser() {
            PointInfo info = pointFacade.chargeByAdmin(admin.getId(), user.getId(), 200L);

            assertThat(info.userId()).isEqualTo(user.getId());
            assertThat(info.balance()).isEqualTo(1_200L);
            assertThat(fixtures.balanceOf(admin.getId())).isZero();
        }

        @DisplayName("[FR-ADMIN-POINT-01 USER_NOT_FOUND] 대상 사용자가 없으면 거절.")
        @Test
        void throwsUserNotFound_whenTargetMissing() {
            assertThrowsErrorType(() -> pointFacade.chargeByAdmin(admin.getId(), 999L, 200L), ErrorType.USER_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-POINT-01 BAD_REQUEST][ER-22] 대상 userId 가 누락되면 요청 형식 오류로 거절 (EP-27).")
        @Test
        void throwsBadRequest_whenTargetUserIdNull() {
            assertThrowsErrorType(() -> pointFacade.chargeByAdmin(admin.getId(), null, 200L), ErrorType.BAD_REQUEST);
        }

        @DisplayName("[FR-ADMIN-POINT-01 NOT_ADMIN] 요청자가 관리자가 아니면 거절, 대상 잔액 유지.")
        @Test
        void throwsNotAdmin() {
            UserModel other = fixtures.user();

            assertThrowsErrorType(() -> pointFacade.chargeByAdmin(other.getId(), user.getId(), 200L), ErrorType.NOT_ADMIN);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[FR-ADMIN-POINT-01 INVALID_AMOUNT] 잔액 유지.")
        @Test
        void throwsInvalidAmount() {
            assertThrowsErrorType(() -> pointFacade.chargeByAdmin(admin.getId(), user.getId(), -1L), ErrorType.INVALID_AMOUNT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[FR-ADMIN-POINT-01 BALANCE_LIMIT_EXCEEDED] 잔액 유지.")
        @Test
        void throwsBalanceLimitExceeded() {
            UserModel rich = fixtures.userWithBalance(Long.MAX_VALUE);

            assertThrowsErrorType(() -> pointFacade.chargeByAdmin(admin.getId(), rich.getId(), 1L), ErrorType.BALANCE_LIMIT_EXCEEDED);

            assertThat(fixtures.balanceOf(rich.getId())).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-POINT-02 사용자 포인트 차감 [추가]")
    class DeductByAdmin {
        @DisplayName("[FR-ADMIN-POINT-02][INV-01] 대상 사용자의 잔액에서 빠진다. 0 까지 가능.")
        @Test
        void deductsTargetUser() {
            PointInfo info = pointFacade.deductByAdmin(admin.getId(), user.getId(), 1_000L);

            assertThat(info.balance()).isZero();
        }

        @DisplayName("[FR-ADMIN-POINT-02 USER_NOT_FOUND] 대상 사용자가 없으면 거절.")
        @Test
        void throwsUserNotFound_whenTargetMissing() {
            assertThrowsErrorType(() -> pointFacade.deductByAdmin(admin.getId(), 999L, 1L), ErrorType.USER_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-POINT-02 BAD_REQUEST][ER-22] 대상 userId 가 누락되면 요청 형식 오류로 거절 (EP-28).")
        @Test
        void throwsBadRequest_whenTargetUserIdNull() {
            assertThrowsErrorType(() -> pointFacade.deductByAdmin(admin.getId(), null, 1L), ErrorType.BAD_REQUEST);
        }

        @DisplayName("[FR-ADMIN-POINT-02 INVALID_AMOUNT] 잔액 유지.")
        @Test
        void throwsInvalidAmount() {
            assertThrowsErrorType(() -> pointFacade.deductByAdmin(admin.getId(), user.getId(), 0L), ErrorType.INVALID_AMOUNT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[FR-ADMIN-POINT-02 INSUFFICIENT_POINT] 음수로 만드는 차감은 거절, 잔액 유지.")
        @Test
        void throwsInsufficientPoint() {
            assertThrowsErrorType(() -> pointFacade.deductByAdmin(admin.getId(), user.getId(), 1_001L), ErrorType.INSUFFICIENT_POINT);

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }
    }
}
