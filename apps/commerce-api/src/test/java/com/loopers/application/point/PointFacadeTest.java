package com.loopers.application.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PointFacadeTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PointRepository pointRepository;

    @InjectMocks
    private PointFacade pointFacade;

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class ChargePoint {
        @DisplayName("기존 잔액이 있으면, 충전 후 잔액을 반환한다.")
        @Test
        void returnsChargedBalance_whenPointExists() {
            // arrange
            Point point = new Point(1L);
            point.charge(1000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));
            given(pointRepository.save(any(Point.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            long result = pointFacade.charge(1L, 500L);

            // assert
            assertThat(result).isEqualTo(1500L);
        }

        @DisplayName("충전한 적 없는 사용자면, 새 포인트를 만들어 충전한다.")
        @Test
        void createsPoint_whenPointIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(pointRepository.save(any(Point.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            long result = pointFacade.charge(1L, 1000L);

            // assert
            assertThat(result).isEqualTo(1000L);
            verify(pointRepository).save(any(Point.class));
        }

        @DisplayName("0 이하 금액이면, BAD_REQUEST 예외가 발생하고 저장하지 않는다.")
        @Test
        void doesNotSave_whenAmountIsNotPositive() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(new Point(1L)));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointFacade.charge(1L, 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(pointRepository, never()).save(any(Point.class));
        }

        @DisplayName("존재하지 않는 사용자면, UNAUTHORIZED 예외가 발생하고 저장하지 않는다.")
        @Test
        void throwsUnauthorizedException_whenUserIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(false);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointFacade.charge(1L, 1000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
            verify(pointRepository, never()).save(any(Point.class));
        }
    }

    @DisplayName("포인트 잔액을 조회할 때, ")
    @Nested
    class GetBalance {
        @DisplayName("저장된 잔액이 있으면, 해당 잔액을 반환한다.")
        @Test
        void returnsStoredBalance_whenPointExists() {
            // arrange
            Point point = new Point(1L);
            point.charge(1000L);
            given(userRepository.existsById(1L)).willReturn(true);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(point));

            // act
            long result = pointFacade.getBalance(1L);

            // assert
            assertThat(result).isEqualTo(1000L);
        }

        @DisplayName("충전한 적 없는 사용자면, 0원을 반환한다.")
        @Test
        void returnsZero_whenPointIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.empty());

            // act
            long result = pointFacade.getBalance(1L);

            // assert
            assertThat(result).isZero();
        }

        @DisplayName("존재하지 않는 사용자면, UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorizedException_whenUserIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(false);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointFacade.getBalance(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }
}
