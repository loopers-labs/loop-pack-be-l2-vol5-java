package com.loopers.infrastructure.shopping.user;

import com.loopers.application.shopping.user.UserQueryDao;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@RequiredArgsConstructor
// 로컬 환경용 초기 사용자/지갑 데이터 생성기
public class LocalUserFixtureInitializer implements ApplicationRunner {
    static final long FIRST_USER_ID = 1L;
    static final long SECOND_USER_ID = 2L;

    private final UserRepository userRepository;
    private final UserQueryDao userQueryDao;
    private final WalletRepository walletRepository;

    // 고정 사용자 2명을 없으면 생성
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        saveIfMissing(FIRST_USER_ID);
        saveIfMissing(SECOND_USER_ID);
    }

    private void saveIfMissing(long userId) {
        if (userQueryDao.findById(userId).isEmpty()) {
            userRepository.save(User.create(userId));
        }
        if (walletRepository.findByUserId(userId).isEmpty()) {
            walletRepository.save(Wallet.zero(userId));
        }
    }
}
