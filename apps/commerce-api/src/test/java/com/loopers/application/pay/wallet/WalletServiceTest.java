package com.loopers.application.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
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
}
