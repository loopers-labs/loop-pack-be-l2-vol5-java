package com.loopers.infrastructure.shopping.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.user.UserQueryDao;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class LocalUserFixtureInitializerIntegrationTest {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQueryDao userQueryDao;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("실제 DB에서 fixture를 초기화할 때")
    @Nested
    class Initialize {
        @DisplayName("각 트랜잭션에서 반복 실행해도 기존 사용자와 fixture를 보존한다")
        @Test
        void initializesIdempotentlyAcrossTransactions() {
            // arrange
            userRepository.save(User.create(1L));
            userRepository.save(User.create(3L));
            LocalUserFixtureInitializer initializer =
                new LocalUserFixtureInitializer(userRepository, userQueryDao, walletRepository);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            // act
            transaction.executeWithoutResult(status -> initializer.run(null));
            transaction.executeWithoutResult(status -> initializer.run(null));

            // assert
            List<Long> ids = jdbcClient.sql("SELECT id FROM users ORDER BY id").query(Long.class).list();
            assertThat(ids).containsExactly(1L, 2L, 3L);
            List<Long> walletUserIds = jdbcClient.sql("SELECT user_id FROM wallets ORDER BY user_id").query(Long.class).list();
            assertThat(walletUserIds).containsExactly(1L, 2L);
        }
    }
}
