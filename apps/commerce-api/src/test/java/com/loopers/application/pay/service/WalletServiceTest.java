package com.loopers.application.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.application.pay.command.WalletCommand;
import com.loopers.application.pay.result.WalletResult;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.model.PointBillType;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.PointBillRepository;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WalletServiceTest {

    @DisplayName("포인트 충전")
    @Nested
    class Charge {
        @DisplayName("잔액을 늘려 저장한 뒤 CHARGE 기록을 순서대로 저장한다")
        @Test
        void chargesBalance_andSavesBillInOrder() {
            // arrange
            WalletRepository walletRepository = mock(WalletRepository.class);
            PointBillRepository pointBillRepository = mock(PointBillRepository.class);
            Wallet wallet = Wallet.zero(1L);
            given(walletRepository.findByUserIdForUpdate(1L)).willReturn(Optional.of(wallet));
            given(walletRepository.save(wallet)).willReturn(wallet);
            given(pointBillRepository.save(any(PointBill.class))).willAnswer(invocation -> invocation.getArgument(0));
            WalletService service = new WalletService(walletRepository, pointBillRepository);

            // act
            WalletResult result = service.execute(new WalletCommand.Charge(1L, 1_000L));

            // assert
            assertThat(result.balance()).isEqualTo(1_000L);
            InOrder order = inOrder(walletRepository, pointBillRepository);
            order.verify(walletRepository).findByUserIdForUpdate(1L);
            order.verify(walletRepository).save(wallet);
            order.verify(pointBillRepository).save(any(PointBill.class));
        }

        @DisplayName("충전 후 잔액이 범위를 초과하면 저장 없이 거절한다")
        @Test
        void rejectsOverflow_withoutSavingAnything() {
            // arrange
            WalletRepository walletRepository = mock(WalletRepository.class);
            PointBillRepository pointBillRepository = mock(PointBillRepository.class);
            Wallet wallet = Wallet.restore(1L, Long.MAX_VALUE);
            given(walletRepository.findByUserIdForUpdate(1L)).willReturn(Optional.of(wallet));
            WalletService service = new WalletService(walletRepository, pointBillRepository);

            // act & assert
            assertThatThrownBy(() -> service.execute(new WalletCommand.Charge(1L, 1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
            verify(walletRepository, never()).save(any());
            verify(pointBillRepository, never()).save(any());
        }

        @DisplayName("비양수 충전액은 저장 없이 거절한다")
        @Test
        void rejectsNonPositiveAmount_withoutSavingAnything() {
            // arrange
            WalletRepository walletRepository = mock(WalletRepository.class);
            PointBillRepository pointBillRepository = mock(PointBillRepository.class);
            given(walletRepository.findByUserIdForUpdate(1L)).willReturn(Optional.of(Wallet.zero(1L)));
            WalletService service = new WalletService(walletRepository, pointBillRepository);

            // act & assert
            assertThatThrownBy(() -> service.execute(new WalletCommand.Charge(1L, 0L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.NON_POSITIVE_MONEY);
            verify(walletRepository, never()).save(any());
            verify(pointBillRepository, never()).save(any());
        }
    }

    @DisplayName("지갑 잠금 조회")
    @Nested
    class LockByUserId {
        @DisplayName("쓰기 잠금으로 조회한 지갑을 반환한다")
        @Test
        void returnsLockedWallet() {
            WalletRepository walletRepository = mock(WalletRepository.class);
            Wallet wallet = Wallet.zero(1L);
            given(walletRepository.findByUserIdForUpdate(1L)).willReturn(Optional.of(wallet));
            WalletService service = new WalletService(walletRepository, mock(PointBillRepository.class));

            assertThat(service.lockByUserId(1L)).isSameAs(wallet);
            verify(walletRepository).findByUserIdForUpdate(1L);
        }
    }

    @DisplayName("주문 결제")
    @Nested
    class Pay {
        private final WalletRepository walletRepository = mock(WalletRepository.class);
        private final PointBillRepository pointBillRepository = mock(PointBillRepository.class);
        private final WalletService service = new WalletService(walletRepository, pointBillRepository);

        @DisplayName("주문 합계만큼 잔액을 차감하고 지갑, 사용 기록 순서로 저장한다")
        @Test
        void deductsOrderTotal_andSavesWalletThenBill() {
            Wallet wallet = Wallet.restore(1L, 10_000L);
            Order order = order(2_000L);
            given(pointBillRepository.save(any(PointBill.class))).willAnswer(invocation -> invocation.getArgument(0));

            PointBill bill = service.pay(wallet, order);

            assertThat(wallet.getBalance()).isEqualTo(8_000L);
            assertThat(bill.getUserId()).isEqualTo(1L);
            assertThat(bill.getType()).isEqualTo(PointBillType.USE);
            assertThat(bill.getOrderId()).isEqualTo(1L);
            assertThat(bill.getAmount()).isEqualTo(2_000L);
            InOrder inOrder = inOrder(walletRepository, pointBillRepository);
            inOrder.verify(walletRepository).save(wallet);
            inOrder.verify(pointBillRepository).save(bill);
        }

        @DisplayName("잔액이 결제액과 같으면 결제하고 0이 된다")
        @Test
        void pays_whenBalanceEqualsTotal() {
            Wallet wallet = Wallet.restore(1L, 2_000L);
            given(pointBillRepository.save(any(PointBill.class))).willAnswer(invocation -> invocation.getArgument(0));

            service.pay(wallet, order(2_000L));

            assertThat(wallet.getBalance()).isZero();
        }

        @DisplayName("잔액이 결제액보다 1 부족하면 INSUFFICIENT_POINT로 거절하고 잔액과 저장을 유지한다")
        @Test
        void rejectsInsufficientBalance_andKeepsState() {
            Wallet wallet = Wallet.restore(1L, 1_999L);
            Order order = order(2_000L);

            assertThatThrownBy(() -> service.pay(wallet, order))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            assertThat(wallet.getBalance()).isEqualTo(1_999L);
            verify(walletRepository, never()).save(any());
            verify(pointBillRepository, never()).save(any());
        }

        private Order order(long totalAmount) {
            return Order.restore(1L, 1L, OrderStatus.DRAFT,
                List.of(OrderItem.restore(10L, "상품", totalAmount, 1, totalAmount)), totalAmount, Instant.now(), null);
        }
    }
}
