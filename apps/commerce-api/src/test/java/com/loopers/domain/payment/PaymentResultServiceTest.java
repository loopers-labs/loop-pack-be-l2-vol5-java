package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TX B 의 "⑥ 문지기가 1행일 때만 ⑦⑧" 규칙과 실패 쪽 "예약 반환 1행일 때만 재고 복원" 규칙을 협력 객체 호출로 검증한다.
 * 실제 동시성·롤백은 PaymentResultServiceIntegrationTest·PaymentResultServiceFailureTest 가 MySQL 로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentResultServiceTest {

    private static final Long ATTEMPT_ID = 7L;
    private static final Long ORDER_ID = 100L;
    private static final String TRANSACTION_KEY = "FAKE-TX-000001";

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private PaymentResultService paymentResultService;

    private final StockReservation reservation = new StockReservation(ORDER_ID, 10L, 2);

    @BeforeEach
    void setUp() {
        given(paymentAttemptRepository.findById(ATTEMPT_ID)).willReturn(Optional.of(
            new PaymentAttempt(ORDER_ID, 1L, "k1", PaymentAttempt.providerOrderIdOf(ORDER_ID), 2000L, "TEST")));
    }

    @DisplayName("최종 결과를 적용할 때, ")
    @Nested
    class ApplyFinal {
        @DisplayName("⑥ 문지기가 0행이면(이미 종결), 주문·예약·재고를 전혀 건드리지 않고 false 를 돌려준다.")
        @Test
        void doesNothing_whenAttemptIsAlreadySettled() {
            // arrange
            given(paymentAttemptRepository.settleIfOpen(ATTEMPT_ID, PaymentAttempt.Status.SUCCESS, null, TRANSACTION_KEY)).willReturn(0);

            // act
            boolean applied = paymentResultService.applyFinal(ATTEMPT_ID, PaymentFinalResult.success(TRANSACTION_KEY));

            // assert
            assertThat(applied).isFalse();
            verify(orderRepository, never()).finishIfPaymentPending(anyLong(), any(), anyLong());
            verify(stockReservationRepository, never()).findAllByOrderId(anyLong());
            verify(productRepository, never()).restoreStock(anyLong(), anyInt());
        }

        @DisplayName("SUCCESS 면, 주문을 CONFIRMED + 결제액으로 바꾸고 예약을 소비하며 재고는 추가로 건드리지 않는다.")
        @Test
        void confirmsOrderAndConsumesReservations_whenSuccess() {
            // arrange
            given(paymentAttemptRepository.settleIfOpen(ATTEMPT_ID, PaymentAttempt.Status.SUCCESS, null, TRANSACTION_KEY)).willReturn(1);
            given(stockReservationRepository.findAllByOrderId(ORDER_ID)).willReturn(List.of(reservation));

            // act
            boolean applied = paymentResultService.applyFinal(ATTEMPT_ID, PaymentFinalResult.success(TRANSACTION_KEY));

            // assert
            assertThat(applied).isTrue();
            verify(orderRepository).finishIfPaymentPending(ORDER_ID, Order.OrderStatus.CONFIRMED, 2000L);
            verify(stockReservationRepository).consumeIfReserved(reservation.getId());
            verify(stockReservationRepository, never()).releaseIfReserved(anyLong());
            verify(productRepository, never()).restoreStock(anyLong(), anyInt());
        }

        @DisplayName("FAILED 면, 주문을 PAYMENT_FAILED·결제액 0 으로 바꾸고 예약 반환이 1행일 때만 재고를 복원한다.")
        @Test
        void failsOrderAndRestoresStockOnce_whenFailed() {
            // arrange
            PaymentFinalResult failed = PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY);
            given(paymentAttemptRepository.settleIfOpen(
                ATTEMPT_ID, PaymentAttempt.Status.FAILED, PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY)).willReturn(1);
            given(stockReservationRepository.findAllByOrderId(ORDER_ID)).willReturn(List.of(reservation));
            given(stockReservationRepository.releaseIfReserved(reservation.getId())).willReturn(1);

            // act
            boolean applied = paymentResultService.applyFinal(ATTEMPT_ID, failed);

            // assert
            assertThat(applied).isTrue();
            verify(orderRepository).finishIfPaymentPending(ORDER_ID, Order.OrderStatus.PAYMENT_FAILED, 0L);
            verify(productRepository).restoreStock(10L, 2);
            verify(stockReservationRepository, never()).consumeIfReserved(anyLong());
        }

        @DisplayName("FAILED 인데 예약 반환이 0행이면(이미 반환됨), 재고를 복원하지 않는다.")
        @Test
        void doesNotRestoreStock_whenReservationWasAlreadyReleased() {
            // arrange
            PaymentFinalResult failed = PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY);
            given(paymentAttemptRepository.settleIfOpen(
                ATTEMPT_ID, PaymentAttempt.Status.FAILED, PaymentAttempt.FailureReason.PG_FAILED, TRANSACTION_KEY)).willReturn(1);
            given(stockReservationRepository.findAllByOrderId(ORDER_ID)).willReturn(List.of(reservation));
            given(stockReservationRepository.releaseIfReserved(reservation.getId())).willReturn(0);

            // act
            paymentResultService.applyFinal(ATTEMPT_ID, failed);

            // assert
            verify(productRepository, never()).restoreStock(anyLong(), anyInt());
        }
    }
}