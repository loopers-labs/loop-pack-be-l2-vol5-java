package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TX A 의 판정 순서(소유권 → 기존 시도 → DRAFT)와 재고 확보 규칙을 협력 객체 호출로 검증한다.
 * 실제 잠금·롤백은 OrderPaymentIntegrationTest 가 MySQL 로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentStartServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 100L;
    private static final PaymentStartCommand COMMAND = new PaymentStartCommand("k1", "TEST");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private ProductService productService;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @InjectMocks
    private PaymentStartService paymentStartService;

    @DisplayName("처음 오는 DRAFT 주문이면, ")
    @Nested
    class FirstStart {
        @DisplayName("주문을 PAYMENT_PENDING·PG 로 바꾸고, 상품 ID 오름차순으로 재고를 차감하며 예약과 REQUESTED 시도를 저장한 뒤 STARTED 를 돌려준다.")
        @Test
        void reservesStockInProductIdOrder_andSavesRequestedAttempt() {
            // arrange - 품목은 상품 ID 역순으로 담아 정렬 여부를 확인한다
            Order order = new Order(USER_ID, List.of(
                new OrderItem(20L, 1, 500L),
                new OrderItem(10L, 2, 1000L)
            ));
            givenLockedDraftOrder(order);
            given(paymentAttemptRepository.save(any(PaymentAttempt.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            PaymentStart result = paymentStartService.start(USER_ID, ORDER_ID, COMMAND);

            // assert
            assertThat(result.isStarted()).isTrue();
            assertThat(result.amount()).isEqualTo(2500L);
            assertThat(result.providerOrderId()).isEqualTo("LP-ORD-0000000100");
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentMethod()).isEqualTo(Order.PaymentMethod.PG);

            InOrder inOrder = inOrder(productService);
            inOrder.verify(productService).deductStock(10L, 2);
            inOrder.verify(productService).deductStock(20L, 1);
            verify(stockReservationRepository, times(2)).save(any(StockReservation.class));

            ArgumentCaptor<PaymentAttempt> saved = ArgumentCaptor.forClass(PaymentAttempt.class);
            verify(paymentAttemptRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
            assertThat(saved.getValue().getAttemptKey()).isEqualTo("k1");
            assertThat(saved.getValue().getCardType()).isEqualTo("TEST");
            assertThat(saved.getValue().getAmount()).isEqualTo(2500L);
            assertThat(saved.getValue().getTransactionKey()).isNull();
        }

        @DisplayName("같은 상품이 합산된 주문이면, 합산된 총수량 기준으로 재고를 차감한다.")
        @Test
        void deductsStockByMergedTotalQuantity() {
            // arrange
            Order order = new Order(USER_ID, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(10L, 3, 1000L)
            ));
            givenLockedDraftOrder(order);
            given(paymentAttemptRepository.save(any(PaymentAttempt.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            paymentStartService.start(USER_ID, ORDER_ID, COMMAND);

            // assert
            verify(productService).deductStock(10L, 5);
        }

        @DisplayName("앞선 상품의 재고가 부족하면, CONFLICT 예외가 전파되고 뒤 상품의 재고 차감과 시도 저장은 일어나지 않는다.")
        @Test
        void stopsDeductingStock_whenEarlierProductStockIsInsufficient() {
            // arrange
            Order order = new Order(USER_ID, List.of(
                new OrderItem(10L, 2, 1000L),
                new OrderItem(20L, 1, 500L)
            ));
            givenLockedDraftOrder(order);
            willThrow(new CoreException(ErrorType.CONFLICT, "재고가 부족합니다."))
                .given(productService).deductStock(10L, 2);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(USER_ID, ORDER_ID, COMMAND);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            verify(productService, never()).deductStock(20L, 1);
            verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
        }

        private void givenLockedDraftOrder(Order order) {
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
            given(paymentAttemptRepository.findByUserIdAndAttemptKey(USER_ID, "k1")).willReturn(Optional.empty());
        }
    }

    @DisplayName("이미 시도가 있는 주문이면, ")
    @Nested
    class ExistingAttempt {
        private final Order order = new Order(USER_ID, List.of(new OrderItem(10L, 2, 1000L)));

        @DisplayName("같은 키·같은 요청(금액·cardType)이면, 재고를 건드리지 않고 저장된 시도를 EXISTING 으로 돌려준다.")
        @Test
        void returnsExisting_whenSameKeyAndSameRequest() {
            // arrange
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(attempt("k1", 2000L, "TEST")));

            // act
            PaymentStart result = paymentStartService.start(USER_ID, ORDER_ID, COMMAND);

            // assert
            assertThat(result.isStarted()).isFalse();
            verify(productService, never()).deductStock(anyLong(), anyInt());
            verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
        }

        @DisplayName("다른 키로 오면, CONFLICT 예외가 발생하고 재고를 건드리지 않는다.")
        @Test
        void throwsConflict_whenDifferentKeyIsUsed() {
            // arrange
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(attempt("k1", 2000L, "TEST")));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(USER_ID, ORDER_ID, new PaymentStartCommand("k2", "TEST"));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("이미 결제 시도가 있는 주문입니다.");
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }

        @DisplayName("같은 키인데 요청 내용(금액)이 다르면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflict_whenSameKeyHasDifferentRequest() {
            // arrange
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(attempt("k1", 999L, "TEST")));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(USER_ID, ORDER_ID, COMMAND);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("같은 결제 시도 키의 요청 내용이 다릅니다.");
        }

        private PaymentAttempt attempt(String key, long amount, String cardType) {
            return new PaymentAttempt(ORDER_ID, USER_ID, key, PaymentAttempt.providerOrderIdOf(ORDER_ID), amount, cardType);
        }
    }

    @DisplayName("시작할 수 없는 주문이면, ")
    @Nested
    class Rejected {
        @DisplayName("요청자가 주문자와 다르면, 주문 존재를 알리지 않고 NOT_FOUND 예외가 발생하며 시도를 조회하지 않는다.")
        @Test
        void throwsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Order order = new Order(USER_ID, List.of(new OrderItem(10L, 2, 1000L)));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(9L, ORDER_ID, COMMAND);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(paymentAttemptRepository, never()).findByOrderId(anyLong());
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }

        @DisplayName("같은 키가 이미 다른 주문에 쓰였으면, CONFLICT 예외가 발생하고 재고를 건드리지 않는다.")
        @Test
        void throwsConflict_whenKeyWasUsedForAnotherOrder() {
            // arrange
            Order order = new Order(USER_ID, List.of(new OrderItem(10L, 2, 1000L)));
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
            given(paymentAttemptRepository.findByUserIdAndAttemptKey(USER_ID, "k1"))
                .willReturn(Optional.of(new PaymentAttempt(200L, USER_ID, "k1", "LP-ORD-0000000200", 500L, "TEST")));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(USER_ID, ORDER_ID, COMMAND);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }

        @DisplayName("이전에 POINT 로 확정된(CONFIRMED, 시도 없음) 주문이면, 기존 '이미 확정된 주문' CONFLICT 예외가 발생한다.")
        @Test
        void throwsAlreadyConfirmed_whenOrderWasConfirmedWithoutAttempt() {
            // arrange
            Order confirmed = mock(Order.class);
            given(confirmed.isOwnedBy(USER_ID)).willReturn(true);
            given(confirmed.getTotalAmount()).willReturn(2000L);
            given(confirmed.getStatus()).willReturn(Order.OrderStatus.CONFIRMED);
            given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(confirmed));
            given(paymentAttemptRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
            given(paymentAttemptRepository.findByUserIdAndAttemptKey(USER_ID, "k1")).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                paymentStartService.start(USER_ID, ORDER_ID, COMMAND);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(result.getMessage()).isEqualTo("이미 확정된 주문입니다.");
            verify(productService, never()).deductStock(anyLong(), anyInt());
        }
    }
}