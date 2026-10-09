package com.loopers.application;

import com.loopers.application.order.OrderConfirmRetrier;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointChargeRetrier;
import com.loopers.application.point.PointFacade;
import com.loopers.application.point.PointInfo;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderErrorCode;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointErrorCode;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ~Retrier 의 재시도 범위와 한도를 확인함. 재시도는 AOP 라 Spring 컨텍스트에서 Facade 를 대역으로 바꿔 끼움 (3주차 설계 4.4, 6.5).
 * 실제 버전 충돌이 나는지는 경쟁 테스트(실제 DB)가 확인함
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetrierTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;

    @Autowired
    private OrderConfirmRetrier orderConfirmRetrier;

    @Autowired
    private PointChargeRetrier pointChargeRetrier;

    @MockitoBean
    private OrderFacade orderFacade;

    @MockitoBean
    private PointFacade pointFacade;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static ObjectOptimisticLockingFailureException conflictOn(Class<?> entity) {
        return new ObjectOptimisticLockingFailureException(entity, 1L);
    }

    @DisplayName("주문 확정을 다시 실행할 때, ")
    @Nested
    class OrderConfirm {
        @DisplayName("충돌이 두 번 나고 세 번째에 성공하면, 확정을 세 번 실행하고 성공한다.")
        @Test
        void succeeds_onThirdAttempt() {
            // arrange
            when(orderFacade.confirmOrder(USER_ID, ORDER_ID))
                .thenThrow(conflictOn(Point.class))
                .thenThrow(conflictOn(Order.class))
                .thenReturn(null);

            // act
            orderConfirmRetrier.confirmOrder(USER_ID, ORDER_ID);

            // assert
            verify(orderFacade, times(3)).confirmOrder(USER_ID, ORDER_ID);
        }

        @DisplayName("세 번 모두 충돌하면, 세 번 실행한 뒤 충돌 예외가 그대로 나간다.")
        @Test
        void throwsConflict_whenAllThreeAttemptsConflict() {
            // arrange
            when(orderFacade.confirmOrder(USER_ID, ORDER_ID)).thenThrow(conflictOn(Point.class));

            // act & assert
            assertThrows(ObjectOptimisticLockingFailureException.class, () -> orderConfirmRetrier.confirmOrder(USER_ID, ORDER_ID));
            verify(orderFacade, times(3)).confirmOrder(USER_ID, ORDER_ID);
        }

        @DisplayName("업무 거절이면, 다시 실행하지 않고 원래 오류 코드가 그대로 나간다.")
        @Test
        void doesNotRetry_whenRejectedByBusinessRule() {
            // arrange
            when(orderFacade.confirmOrder(USER_ID, ORDER_ID)).thenThrow(new CoreException(OrderErrorCode.ORDER_ALREADY_CONFIRMED));

            // act
            CoreException result = assertThrows(CoreException.class, () -> orderConfirmRetrier.confirmOrder(USER_ID, ORDER_ID));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> verify(orderFacade, times(1)).confirmOrder(USER_ID, ORDER_ID)
            );
        }
    }

    @DisplayName("포인트 충전을 다시 실행할 때, ")
    @Nested
    class PointCharge {
        @DisplayName("충돌이 두 번 나고 세 번째에 성공하면, 충전을 세 번 실행하고 결과를 돌려준다.")
        @Test
        void succeeds_onThirdAttempt() {
            // arrange
            when(pointFacade.charge(USER_ID, 2_000L))
                .thenThrow(conflictOn(Point.class))
                .thenThrow(conflictOn(Point.class))
                .thenReturn(new PointInfo(12_000L));

            // act
            PointInfo result = pointChargeRetrier.charge(USER_ID, 2_000L);

            // assert
            assertAll(
                () -> assertThat(result.balance()).isEqualTo(12_000L),
                () -> verify(pointFacade, times(3)).charge(USER_ID, 2_000L)
            );
        }

        @DisplayName("세 번 모두 충돌하면, 세 번 실행한 뒤 충돌 예외가 그대로 나간다.")
        @Test
        void throwsConflict_whenAllThreeAttemptsConflict() {
            // arrange
            when(pointFacade.charge(USER_ID, 2_000L)).thenThrow(conflictOn(Point.class));

            // act & assert
            assertThrows(ObjectOptimisticLockingFailureException.class, () -> pointChargeRetrier.charge(USER_ID, 2_000L));
            verify(pointFacade, times(3)).charge(USER_ID, 2_000L);
        }

        @DisplayName("업무 거절이면, 다시 실행하지 않고 원래 오류 코드가 그대로 나간다.")
        @Test
        void doesNotRetry_whenRejectedByBusinessRule() {
            // arrange
            when(pointFacade.charge(USER_ID, 0L)).thenThrow(new CoreException(PointErrorCode.INVALID_CHARGE_AMOUNT));

            // act
            CoreException result = assertThrows(CoreException.class, () -> pointChargeRetrier.charge(USER_ID, 0L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PointErrorCode.INVALID_CHARGE_AMOUNT),
                () -> verify(pointFacade, times(1)).charge(USER_ID, 0L)
            );
        }
    }

    @DisplayName("충돌이 재시도 한도를 넘으면, 409 와 CONCURRENT_UPDATE_CONFLICT 를 돌려준다.")
    @Test
    void respondsConcurrentUpdateConflict_whenRetriesAreExhausted() throws Exception {
        // arrange
        User user = userJpaRepository.save(new User());
        when(pointFacade.charge(anyLong(), anyLong())).thenThrow(conflictOn(Point.class));

        // act & assert
        mvc.perform(post("/api/v1/points/charge")
                .header("X-USER-ID", String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 2000}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.meta.errorCode").value("CONCURRENT_UPDATE_CONFLICT"));
        verify(pointFacade, times(3)).charge(user.getId(), 2_000L);
    }
}
