package com.loopers.application.pay.wallet;

import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 지갑 충전 처리 서비스
public class WalletService implements ChargeWalletUseCase {
    private final WalletRepository walletRepository;
    private final PointBillRepository pointBillRepository;

    // 잔액 충전 후 충전 기록 저장
    @Override
    @Transactional
    public WalletResult execute(WalletCommand.Charge command) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(command.userId()).orElseThrow();
        PointBill pointBill = wallet.charge(Money.positive(command.amount()));
        Wallet saved = walletRepository.save(wallet);
        pointBillRepository.save(pointBill);
        return new WalletResult(saved.getBalance());
    }
}
