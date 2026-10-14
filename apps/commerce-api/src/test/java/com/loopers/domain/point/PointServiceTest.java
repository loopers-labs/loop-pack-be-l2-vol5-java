package com.loopers.domain.point;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @Mock
    private PointRepository pointRepository;

    @InjectMocks
    private PointService pointService;

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("포인트가 있으면, 잔액을 더하는 갱신 뒤 재조회한 잔액을 반환하고 새로 저장하지 않는다.")
        @Test
        void addsBalanceAndReturnsReloadedBalance_whenPointExists() {
            // arrange
            Point charged = new Point(1L);
            charged.charge(1500L);
            given(pointRepository.addBalance(1L, 500L)).willReturn(1);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(charged));

            // act
            long result = pointService.charge(1L, 500L);

            // assert
            assertThat(result).isEqualTo(1500L);
            verify(pointRepository).addBalance(1L, 500L);
            verify(pointRepository, never()).save(any(Point.class));
        }

        @DisplayName("충전한 적 없는 사용자면(갱신 0건, 포인트 없음), 새 포인트를 만들어 충전 금액으로 저장한다.")
        @Test
        void createsPoint_whenPointIsAbsent() {
            // arrange
            given(pointRepository.addBalance(1L, 1000L)).willReturn(0);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.empty());
            given(pointRepository.save(any(Point.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            long result = pointService.charge(1L, 1000L);

            // assert
            assertThat(result).isEqualTo(1000L);
            verify(pointRepository).save(any(Point.class));
        }

        @DisplayName("갱신이 0건인데 포인트가 있으면, 표현 범위를 넘는 충전이므로 BAD_REQUEST 예외가 발생하고 저장하지 않는다.")
        @Test
        void throwsBadRequestException_whenResultOverflows() {
            // arrange
            Point nearMax = new Point(1L);
            nearMax.charge(Long.MAX_VALUE);
            given(pointRepository.addBalance(1L, 1L)).willReturn(0);
            given(pointRepository.findByUserId(1L)).willReturn(Optional.of(nearMax));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointService.charge(1L, 1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(result.getMessage()).isEqualTo("충전 결과가 표현 가능한 범위를 넘습니다.");
            verify(pointRepository, never()).save(any(Point.class));
        }

        @DisplayName("0 이하 금액이면, BAD_REQUEST 예외가 발생하고 갱신하지 않는다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointService.charge(1L, 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(result.getMessage()).isEqualTo("충전 금액은 1원 이상이어야 합니다.");
            verify(pointRepository, never()).addBalance(anyLong(), anyLong());
            verify(pointRepository, never()).save(any(Point.class));
        }
    }

    @DisplayName("포인트를 차감할 때, ")
    @Nested
    class Deduct {
        @DisplayName("잔액이 충분해 1건이 갱신되면, 잔액이 충분할 때만 차감하는 조건부 갱신이 실행되고 예외 없이 끝난다.")
        @Test
        void deductsConditionally_whenBalanceIsSufficient() {
            // arrange
            given(pointRepository.deductIfEnough(1L, 2000L)).willReturn(1);

            // act
            pointService.deduct(1L, 2000L);

            // assert
            verify(pointRepository).deductIfEnough(1L, 2000L);
        }

        @DisplayName("조건부 갱신이 0건이면, 잔액 부족으로 보고 CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenNoRowIsUpdated() {
            // arrange
            given(pointRepository.deductIfEnough(1L, 2000L)).willReturn(0);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointService.deduct(1L, 2000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("포인트 잔액이 부족합니다.");
        }

        @DisplayName("0 이하 금액이면, BAD_REQUEST 예외가 발생하고 갱신하지 않는다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointService.deduct(1L, 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(result.getMessage()).isEqualTo("차감 금액은 1원 이상이어야 합니다.");
            verify(pointRepository, never()).deductIfEnough(anyLong(), anyLong());
        }
    }
}
